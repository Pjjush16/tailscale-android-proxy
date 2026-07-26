// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package libtailscale - proxy server mode using tsnet
// This provides a SOCKS5 + HTTP proxy without requiring VPN permissions.

package libtailscale

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

// ProxyServer runs a SOCKS5/HTTP proxy backed by tsnet.
type ProxyServer struct {
	ts         *tsnet.Server
	socksLn    net.Listener
	httpLn     net.Listener
	socksPort  int
	httpPort   int
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	dataDir    string
	hostname   string
	running    bool
	mu         sync.Mutex
	onStatusChange func(string)
}

// NewProxyServer creates a new tsnet-backed proxy server.
func NewProxyServer(dataDir string, hostname string, socksPort int, httpPort int) *ProxyServer {
	ctx, cancel := context.WithCancel(context.Background())
	return &ProxyServer{
		dataDir:   dataDir,
		hostname:  hostname,
		socksPort: socksPort,
		httpPort:  httpPort,
		ctx:       ctx,
		cancel:    cancel,
	}
}

// Start starts the proxy server.
func (ps *ProxyServer) Start() error {
	ps.mu.Lock()
	defer ps.mu.Unlock()

	if ps.running {
		return fmt.Errorf("proxy server already running")
	}

	ps.ts = &tsnet.Server{
		Dir:      ps.dataDir,
		Hostname: ps.hostname,
	}

	// Start tsnet
	if err := ps.ts.Start(); err != nil {
		return fmt.Errorf("tsnet start: %w", err)
	}

	// Wait for tsnet to be ready
	ctx, cancel := context.WithTimeout(ps.ctx, 60*time.Second)
	defer cancel()

	// Wait until the node is up
	lc, err := ps.ts.LocalClient()
	if err != nil {
		ps.ts.Close()
		return fmt.Errorf("tsnet local client: %w", err)
	}

	for {
		status, err := lc.Status(ctx)
		if err != nil {
			ps.ts.Close()
			return fmt.Errorf("tsnet status: %w", err)
		}
		if status.BackendState == "Running" {
			break
		}
		select {
		case <-ctx.Done():
			ps.ts.Close()
			return fmt.Errorf("timeout waiting for tsnet to start")
		case <-time.After(500 * time.Millisecond):
		}
	}

	// Start SOCKS5 proxy
	socksAddr := fmt.Sprintf(":%d", ps.socksPort)
	ps.socksLn, err = net.Listen("tcp", socksAddr)
	if err != nil {
		ps.ts.Close()
		return fmt.Errorf("socks5 listen: %w", err)
	}
	ps.wg.Add(1)
	go ps.socksLoop()

	// Start HTTP proxy
	httpAddr := fmt.Sprintf(":%d", ps.httpPort)
	ps.httpLn, err = net.Listen("tcp", httpAddr)
	if err != nil {
		ps.socksLn.Close()
		ps.ts.Close()
		return fmt.Errorf("http proxy listen: %w", err)
	}
	ps.wg.Add(1)
	go ps.httpLoop()

	ps.running = true
	log.Printf("Proxy server started: SOCKS5 on :%d, HTTP on :%d", ps.socksPort, ps.httpPort)

	if ps.onStatusChange != nil {
		ps.onStatusChange("running")
	}

	return nil
}

// Stop stops the proxy server.
func (ps *ProxyServer) Stop() {
	ps.mu.Lock()
	if !ps.running {
		ps.mu.Unlock()
		return
	}
	ps.running = false
	ps.mu.Unlock()

	ps.cancel()

	if ps.socksLn != nil {
		ps.socksLn.Close()
	}
	if ps.httpLn != nil {
		ps.httpLn.Close()
	}
	if ps.ts != nil {
		ps.ts.Close()
	}

	ps.wg.Wait()
	log.Printf("Proxy server stopped")

	if ps.onStatusChange != nil {
		ps.onStatusChange("stopped")
	}
}

// IsRunning returns whether the proxy is running.
func (ps *ProxyServer) IsRunning() bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.running
}

// SetStatusCallback sets a callback for status changes.
func (ps *ProxyServer) SetStatusCallback(fn func(string)) {
	ps.onStatusChange = fn
}

// GetAuthURL returns the tsnet auth URL if login is needed.
func (ps *ProxyServer) GetAuthURL() string {
	if ps.ts == nil {
		return ""
	}
	lc, err := ps.ts.LocalClient()
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return ""
	}
	if status.BackendState == "NeedsLogin" {
		return status.AuthURL
	}
	return ""
}

// GetIPs returns the Tailscale IPs assigned to this node.
func (ps *ProxyServer) GetIPs() []string {
	if ps.ts == nil {
		return nil
	}
	lc, err := ps.ts.LocalClient()
	if err != nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return nil
	}
	ips := make([]string, 0, len(status.TailscaleIPs))
	for _, ip := range status.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	return ips
}

// ===== SOCKS5 Proxy =====

func (ps *ProxyServer) socksLoop() {
	defer ps.wg.Done()
	for {
		conn, err := ps.socksLn.Accept()
		if err != nil {
			select {
			case <-ps.ctx.Done():
				return
			default:
				continue
			}
		}
		ps.wg.Add(1)
		go ps.handleSocks5(conn)
	}
}

func (ps *ProxyServer) handleSocks5(conn net.Conn) {
	defer ps.wg.Done()
	defer conn.Close()

	// SOCKS5 handshake
	buf := make([]byte, 258)
	if _, err := io.ReadFull(conn, buf[:2]); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}

	nMethods := int(buf[1])
	if _, err := io.ReadFull(conn, buf[:nMethods]); err != nil {
		return
	}

	// No auth
	conn.Write([]byte{0x05, 0x00})

	// Read connect request
	if _, err := io.ReadFull(conn, buf[:4]); err != nil {
		return
	}
	if buf[0] != 0x05 || buf[1] != 0x01 { // Only CONNECT
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var targetAddr string
	switch buf[3] {
	case 0x01: // IPv4
		if _, err := io.ReadFull(conn, buf[:4]); err != nil {
			return
		}
		if _, err := io.ReadFull(conn, buf[4:6]); err != nil {
			return
		}
		port := binary.BigEndian.Uint16(buf[4:6])
		targetAddr = fmt.Sprintf("%d.%d.%d.%d:%d", buf[0], buf[1], buf[2], buf[3], port)
	case 0x03: // Domain
		if _, err := io.ReadFull(conn, buf[:1]); err != nil {
			return
		}
		domLen := int(buf[0])
		if _, err := io.ReadFull(conn, buf[:domLen]); err != nil {
			return
		}
		domain := string(buf[:domLen])
		if _, err := io.ReadFull(conn, buf[:2]); err != nil {
			return
		}
		port := binary.BigEndian.Uint16(buf[:2])
		targetAddr = fmt.Sprintf("%s:%d", domain, port)
	case 0x04: // IPv6
		if _, err := io.ReadFull(conn, buf[:16]); err != nil {
			return
		}
		if _, err := io.ReadFull(conn, buf[16:18]); err != nil {
			return
		}
		port := binary.BigEndian.Uint16(buf[16:18])
		ip := net.IP(buf[:16])
		targetAddr = fmt.Sprintf("[%s]:%d", ip, port)
	default:
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Connect via tsnet
	remote, err := ps.ts.Dial(ps.ctx, "tcp", targetAddr)
	if err != nil {
		conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	// Success
	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Bidirectional copy
	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, conn); done <- struct{}{} }()
	go func() { io.Copy(conn, remote); done <- struct{}{} }()
	<-done
}

// ===== HTTP Proxy =====

func (ps *ProxyServer) httpLoop() {
	defer ps.wg.Done()
	srv := &http.Server{
		Handler: http.HandlerFunc(ps.handleHTTP),
	}
	go func() {
		<-ps.ctx.Done()
		srv.Close()
	}()
	srv.Serve(ps.httpLn)
}

func (ps *ProxyServer) handleHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		ps.handleHTTPConnect(w, r)
		return
	}

	// Regular HTTP proxy: forward through tsnet
	host := r.URL.Host
	if !strings.Contains(host, ":") {
		host += ":80"
	}

	remote, err := ps.ts.Dial(r.Context(), "tcp", host)
	if err != nil {
		http.Error(w, "Connection failed", http.StatusBadGateway)
		return
	}
	defer remote.Close()

	// Remove proxy headers
	r.Header.Del("Proxy-Connection")
	r.RequestURI = ""
	r.URL.Scheme = "http"

	if err := r.Write(remote); err != nil {
		http.Error(w, "Write failed", http.StatusBadGateway)
		return
	}

	// Read response and forward back
	hjc := newHijackConn(w)
	defer hjc.Close()

	done := make(chan struct{}, 2)
	go func() { io.Copy(hjc, remote); done <- struct{}{} }()
	go func() { io.Copy(remote, hjc); done <- struct{}{} }()
	<-done
}

func (ps *ProxyServer) handleHTTPConnect(w http.ResponseWriter, r *http.Request) {
	target, err := ps.ts.Dial(r.Context(), "tcp", r.Host)
	if err != nil {
		http.Error(w, "Connection failed", http.StatusBadGateway)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		target.Close()
		http.Error(w, "Hijack not supported", http.StatusInternalServerError)
		return
	}

	client, _, err := hijacker.Hijack()
	if err != nil {
		target.Close()
		return
	}

	client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	done := make(chan struct{}, 2)
	go func() { io.Copy(target, client); done <- struct{}{} }()
	go func() { io.Copy(client, target); done <- struct{}{} }()
	<-done
	target.Close()
	client.Close()
}

// hijackConn wraps ResponseWriter for raw TCP forwarding
type hijackConn struct {
	w   http.ResponseWriter
	rc  io.ReadCloser
}

func newHijackConn(w http.ResponseWriter) *hijackConn {
	return &hijackConn{w: w}
}

func (h *hijackConn) Read(b []byte) (int, error) {
	if h.rc == nil {
		return 0, io.EOF
	}
	return h.rc.Read(b)
}

func (h *hijackConn) Write(b []byte) (int, error) {
	return h.w.Write(b)
}

func (h *hijackConn) Close() error {
	if h.rc != nil {
		return h.rc.Close()
	}
	return nil
}
