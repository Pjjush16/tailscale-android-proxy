// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package libtailscale - proxy server mode using tsnet
// This provides a SOCKS5 + HTTP proxy without requiring VPN permissions.

package libtailscale

import (
	"context"
	"encoding/binary"
	"encoding/json"
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
	log.Printf("[Proxy] Starting tsnet server (connecting to Tailscale coordination)...")
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
			log.Printf("[Proxy] ✅ Connected to Tailscale network!")
			log.Printf("[Proxy] ✅ Tailscale IPs: %v", status.TailscaleIPs)
			log.Printf("[Proxy] ✅ Peers: %d nodes in tailnet", len(status.Peer))
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

// GetPeers returns the list of peers in the tailnet as JSON.
func (ps *ProxyServer) GetPeers() string {
	if ps.ts == nil {
		return "[]"
	}
	lc, err := ps.ts.LocalClient()
	if err != nil {
		return "[]"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return "[]"
	}
	
	// Build peer list
	type PeerInfo struct {
		Name      string `json:"name"`
		Hostname  string `json:"hostname"`
		IPs       []string `json:"ips"`
		Online    bool `json:"online"`
		OS        string `json:"os"`
		LastSeen  string `json:"lastSeen"`
	}
	
	peers := make([]PeerInfo, 0, len(status.Peer))
	for _, peer := range status.Peer {
		ips := make([]string, 0, len(peer.TailscaleIPs))
		for _, ip := range peer.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		
		peers = append(peers, PeerInfo{
			Name:     peer.DNSName,
			Hostname: peer.HostName,
			IPs:      ips,
			Online:   peer.Online,
			OS:       string(peer.OS),
			LastSeen: peer.LastSeen.Format(time.RFC3339),
		})
	}
	
	// Convert to JSON
	jsonBytes, err := json.Marshal(peers)
	if err != nil {
		return "[]"
	}
	return string(jsonBytes)
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

	// Set read deadline for handshake (10 seconds)
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

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

	// Clear read deadline before connecting
	conn.SetReadDeadline(time.Time{})

	// Connect via tsnet with timeout
	log.Printf("[Proxy] SOCKS5 connect to %s via Tailscale network...", targetAddr)
	dialCtx, dialCancel := context.WithTimeout(ps.ctx, 30*time.Second)
	defer dialCancel()
	remote, err := ps.ts.Dial(dialCtx, "tcp", targetAddr)
	if err != nil {
		log.Printf("[Proxy] ❌ SOCKS5 connect failed: %v", err)
		conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	log.Printf("[Proxy] ✅ SOCKS5 connected to %s", targetAddr)

	// Success
	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Bidirectional copy with context cancellation
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(remote, conn)
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(conn, remote)
	}()
	
	// Wait for one direction to finish, then close both connections
	select {
	case <-done:
	case <-ps.ctx.Done():
	}
	// Close both connections to unblock the other goroutine
	conn.Close()
	remote.Close()
}

// ===== HTTP Proxy =====

func (ps *ProxyServer) httpLoop() {
	defer ps.wg.Done()
	srv := &http.Server{
		Handler:           http.HandlerFunc(ps.handleHTTP),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
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

	log.Printf("[Proxy] HTTP connect to %s via Tailscale network...", host)
	dialCtx, dialCancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer dialCancel()
	remote, err := ps.ts.Dial(dialCtx, "tcp", host)
	if err != nil {
		log.Printf("[Proxy] ❌ HTTP connect failed: %v", err)
		http.Error(w, "Connection failed", http.StatusBadGateway)
		return
	}
	defer remote.Close()
	log.Printf("[Proxy] ✅ HTTP connected to %s", host)

	// Remove proxy headers
	r.Header.Del("Proxy-Connection")
	r.RequestURI = ""
	r.URL.Scheme = "http"

	if err := r.Write(remote); err != nil {
		http.Error(w, "Write failed", http.StatusBadGateway)
		return
	}

	// Read response and forward back with context cancellation
	hjc := newHijackConn(w)
	defer hjc.Close()

	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(hjc, remote)
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(remote, hjc)
	}()
	
	// Wait for one direction to finish, then close both connections
	select {
	case <-done:
	case <-r.Context().Done():
	}
	// Close both connections to unblock the other goroutine
	hjc.Close()
	remote.Close()
}

func (ps *ProxyServer) handleHTTPConnect(w http.ResponseWriter, r *http.Request) {
	log.Printf("[Proxy] HTTP CONNECT to %s via Tailscale network...", r.Host)
	dialCtx, dialCancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer dialCancel()
	target, err := ps.ts.Dial(dialCtx, "tcp", r.Host)
	if err != nil {
		log.Printf("[Proxy] ❌ HTTP CONNECT failed: %v", err)
		http.Error(w, "Connection failed", http.StatusBadGateway)
		return
	}
	log.Printf("[Proxy] ✅ HTTP CONNECT established to %s", r.Host)

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
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(target, client)
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(client, target)
	}()
	
	// Wait for one direction to finish, then close both connections
	select {
	case <-done:
	case <-r.Context().Done():
	}
	// Close both connections to unblock the other goroutine
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
