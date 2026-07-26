// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.BuiltInBrowserActivity
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AppVersion
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel

@Composable
fun SettingsView(
    settingsNav: SettingsNav,
    viewModel: SettingsViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel()
) {
  val handler = LocalUriHandler.current

  val user by viewModel.loggedInUser.collectAsState()
  val isAdmin by viewModel.isAdmin.collectAsState()
  val managedByOrganization by viewModel.managedByOrganization.collectAsState()
  val tailnetLockEnabled by viewModel.tailNetLockEnabled.collectAsState()
  val corpDNSEnabled by viewModel.corpDNSEnabled.collectAsState()
  val isVPNPrepared by appViewModel.vpnPrepared.collectAsState()
  val showTailnetLock by MDMSettings.manageTailnetLock.flow.collectAsState()
  val useTailscaleSubnets by MDMSettings.useTailscaleSubnets.flow.collectAsState()
  val isClientRemoteLoggingEnabled by viewModel.isClientRemoteLoggingEnabled.collectAsState()
  val isSplitTunnelEnabled by viewModel.isSplitTunnelEnabled.collectAsState()
  val isProxyModeEnabled by viewModel.isProxyModeEnabled.collectAsState()
  var showDisableLoggingDialog by remember { mutableStateOf(false) }

  Scaffold(
      topBar = {
        Header(titleRes = R.string.settings_title, onBack = settingsNav.onNavigateBackHome)
      }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
          if (isVPNPrepared) {
            UserView(
                profile = user,
                actionState = UserActionState.NAV,
                onClick = settingsNav.onNavigateToUserSwitcher)
          }

          if (isAdmin && !isAndroidTV()) {
            Lists.ItemDivider()
            AdminTextView { handler.openUri(Links.ADMIN_URL) }
          }

          Lists.SectionDivider()
          Setting.Text(
              R.string.dns_settings,
              subtitle =
                  corpDNSEnabled?.let {
                    stringResource(
                        if (it) R.string.using_tailscale_dns else R.string.not_using_tailscale_dns)
                  },
              onClick = settingsNav.onNavigateToDNSSettings)

          Lists.ItemDivider()
          Setting.Text(
              R.string.split_tunneling,
              subtitle = stringResource(R.string.filter_apps_allowed_to_access_tailscale),
              onClick = settingsNav.onNavigateToSplitTunneling)

          if (showTailnetLock.value == ShowHide.Show) {
            Lists.ItemDivider()
            Setting.Text(
                R.string.tailnet_lock,
                subtitle =
                    tailnetLockEnabled?.let {
                      stringResource(if (it) R.string.enabled else R.string.disabled)
                    },
                onClick = settingsNav.onNavigateToTailnetLock)
          }
          if (useTailscaleSubnets.value == AlwaysNeverUserDecides.UserDecides) {
            Lists.ItemDivider()
            Setting.Text(R.string.subnet_routing, onClick = settingsNav.onNavigateToSubnetRouting)
          }

          Lists.ItemDivider()
          Setting.Switch(
              R.string.client_remote_logging_enabled,
              subtitle =
                  stringResource(
                      if (MDMSettings.isMDMConfigured)
                          R.string.client_remote_logging_enabled_subtitle_mdm
                      else R.string.client_remote_logging_enabled_subtitle),
              isOn = isClientRemoteLoggingEnabled,
              enabled = !MDMSettings.isMDMConfigured,
              onToggle = {
                if (isClientRemoteLoggingEnabled) {
                  showDisableLoggingDialog = true
                } else {
                  viewModel.toggleIsClientRemoteLoggingEnabled()
                }
              })

          Lists.ItemDivider()
          Setting.Switch(
              title = "Split Tunnel Mode (分流模式)",
              subtitle =
                  "Only route Tailscale traffic (100.64.0.0/10). " +
                  "Other traffic uses system default route. " +
                  "This allows other VPN/proxy apps (like Clash) to work simultaneously. " +
                  "Requires VPN restart to take effect.",
              isOn = isSplitTunnelEnabled,
              onToggle = { viewModel.toggleSplitTunnel() })

          Lists.ItemDivider()
          Setting.Switch(
              title = "Proxy Mode (代理模式)",
              subtitle =
                  "Run Tailscale as a local SOCKS5/HTTP proxy without VPN permission. " +
                  "SOCKS5: 127.0.0.1:1080 | HTTP: 127.0.0.1:8080. " +
                  "Does not interfere with other VPN/proxy apps. " +
                  "Configure your apps to use the proxy to access Tailnet.",
              isOn = isProxyModeEnabled,
              onToggle = { viewModel.toggleProxyMode() })

          if (isProxyModeEnabled) {
            Lists.ItemDivider()
            val context = LocalContext.current
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
              Text(
                  text = "Built-in Browser (内置浏览器)",
                  style = MaterialTheme.typography.titleMedium,
                  modifier = Modifier.padding(bottom = 4.dp)
              )
              Text(
                  text = "Access HTTP/FTP services on Tailnet without configuring proxy in browser.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(bottom = 8.dp)
              )
              Button(
                  onClick = {
                    val intent = Intent(context, BuiltInBrowserActivity::class.java)
                    context.startActivity(intent)
                  }
              ) {
                Text("Open Browser (打开浏览器)")
              }
            }
          }

          if (!AndroidTVUtil.isAndroidTV()) {
            Lists.ItemDivider()
            Setting.Text(R.string.permissions, onClick = settingsNav.onNavigateToPermissions)
          }

          managedByOrganization.value?.let {
            Lists.ItemDivider()
            Setting.Text(
                title = stringResource(R.string.managed_by_orgName, it),
                onClick = settingsNav.onNavigateToManagedBy)
          }

          Lists.SectionDivider()
          Setting.Text(R.string.bug_report, onClick = settingsNav.onNavigateToBugReport)

          Lists.ItemDivider()
          Setting.Text(
              R.string.about_tailscale,
              subtitle = "${stringResource(id = R.string.version)} ${AppVersion.Short()}",
              onClick = settingsNav.onNavigateToAbout)

          // TODO: put a heading for the debug section
          if (BuildConfig.DEBUG) {
            Lists.SectionDivider()
            Lists.MutedHeader(text = stringResource(R.string.internal_debug_options))
            Setting.Text(R.string.mdm_settings, onClick = settingsNav.onNavigateToMDMSettings)
          }
        }
      }

  if (showDisableLoggingDialog) {
    AlertDialog(
        onDismissRequest = { showDisableLoggingDialog = false },
        title = { Text(stringResource(R.string.client_remote_logging_disable_confirm_title)) },
        text = { Text(stringResource(R.string.client_remote_logging_disable_confirm_message)) },
        confirmButton = {
          TextButton(
              onClick = {
                showDisableLoggingDialog = false
                viewModel.toggleIsClientRemoteLoggingEnabled()
              }) {
                Text(
                    stringResource(R.string.client_remote_logging_disable_confirm_button),
                    color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = {
          TextButton(onClick = { showDisableLoggingDialog = false }) {
            Text(stringResource(R.string.cancel))
          }
        })
  }
}

object Setting {
  @Composable
  fun Text(
      titleRes: Int = 0,
      title: String? = null,
      subtitle: String? = null,
      destructive: Boolean = false,
      enabled: Boolean = true,
      onClick: (() -> Unit)? = null
  ) {
    var modifier: Modifier = Modifier
    if (enabled) {
      onClick?.let { modifier = modifier.clickable(onClick = it) }
    }
    ListItem(
        modifier = modifier,
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
              color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        supportingContent =
            subtitle?.let {
              {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            })
  }

  @Composable
  fun Switch(
      titleRes: Int = 0,
      title: String? = null,
      subtitle: String? = null,
      isOn: Boolean,
      enabled: Boolean = true,
      onToggle: (Boolean) -> Unit = {}
  ) {
    ListItem(
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        supportingContent =
            subtitle?.let {
              {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            },
        trailingContent = {
          TintedSwitch(checked = isOn, onCheckedChange = onToggle, enabled = enabled)
        })
  }
}

@Composable
fun AdminTextView(onNavigateToAdminConsole: () -> Unit) {
  val adminStr = buildAnnotatedString {
    append(stringResource(id = R.string.settings_admin_prefix))

    pushStringAnnotation(tag = "link", annotation = Links.ADMIN_URL)
    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.settings_admin_link))
        }
  }

  Lists.InfoItem(adminStr, onClick = onNavigateToAdminConsole)
}

@Preview
@Composable
fun SettingsPreview() {
  val vm = SettingsViewModel()
  vm.corpDNSEnabled.set(true)
  vm.tailNetLockEnabled.set(true)
  vm.isAdmin.set(true)
  vm.managedByOrganization.set("Tails and Scales Inc.")
  SettingsView(SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}), vm)
}
