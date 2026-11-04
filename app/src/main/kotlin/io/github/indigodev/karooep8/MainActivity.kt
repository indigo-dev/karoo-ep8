package io.github.indigodev.karooep8

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var permissionsGranted by mutableStateOf(false)
    private var diagnosticReport by mutableStateOf(BleDiagnosticReport())
    private var testScanJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted = BlePermissions.hasScanPermissions(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted = BlePermissions.hasScanPermissions(this)
        if (!permissionsGranted) {
            requestBlePermissions()
        }

        val versionLabel = "v${getAppVersionName()}"

        setContent {
            val extDiag by ExtensionDiagnostics.state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                SetupStatusScreen(
                    versionLabel = versionLabel,
                    permissionsGranted = permissionsGranted,
                    extDiag = extDiag,
                    bleReport = diagnosticReport,
                    onRequestPermissions = ::requestBlePermissions,
                    onToggleBleScan = ::toggleBleDiagnosticScan
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = BlePermissions.hasScanPermissions(this)
    }

    override fun onPause() {
        stopBleDiagnosticScan()
        super.onPause()
    }

    override fun onDestroy() {
        stopBleDiagnosticScan()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun requestBlePermissions() {
        permissionLauncher.launch(BlePermissions.requiredPermissions())
    }

    private fun toggleBleDiagnosticScan() {
        if (testScanJob?.isActive == true) {
            stopBleDiagnosticScan()
            return
        }
        testScanJob = uiScope.launch {
            BleDiagnosticScanner.runTenSecondScan(applicationContext).collect { report ->
                diagnosticReport = report
            }
        }
    }

    private fun stopBleDiagnosticScan() {
        testScanJob?.cancel()
        testScanJob = null
        if (diagnosticReport.isScanning) {
            diagnosticReport = diagnosticReport.copy(isScanning = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun getAppVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.1"
        }.getOrDefault("1.1.1")
    }
}

@Composable
private fun SetupStatusScreen(
    versionLabel: String,
    permissionsGranted: Boolean,
    extDiag: DiagnosticSnapshot,
    bleReport: BleDiagnosticReport,
    onRequestPermissions: () -> Unit,
    onToggleBleScan: () -> Unit
) {
    val badgeColor = if (permissionsGranted) Color(0xFF4CAF50) else Color(0xFFFFA000)
    val noneText = stringResource(R.string.diag_none)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.extension_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = versionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (permissionsGranted) {
                    "● ${stringResource(R.string.status_permissions_granted)}"
                } else {
                    "● ${stringResource(R.string.status_permissions_missing)}"
                },
                color = badgeColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (permissionsGranted) {
                    stringResource(R.string.setup_instructions_ready)
                } else {
                    stringResource(R.string.setup_instructions_grant)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            if (!permissionsGranted) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_grant_permissions))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1E1E)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.diag_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64B5F6)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (extDiag.serviceCreated) {
                            "● ${stringResource(R.string.diag_service_active)}"
                        } else {
                            "○ ${stringResource(R.string.diag_service_waiting)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (extDiag.serviceCreated) Color(0xFF81C784) else Color(0xFFBDBDBD)
                    )

                    Text(
                        text = "${stringResource(R.string.diag_karoo_scans)}: ${extDiag.karooScanCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE0E0E0)
                    )

                    val reportedSummary = if (extDiag.reportedDevices.isEmpty()) {
                        noneText
                    } else {
                        extDiag.reportedDevices.joinToString("\n")
                    }
                    Text(
                        text = "${stringResource(R.string.diag_reported_bikes)}: $reportedSummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE0E0E0)
                    )

                    if (extDiag.lastError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = extDiag.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF8A65)
                        )
                    }

                    if (permissionsGranted) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onToggleBleScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (bleReport.isScanning) {
                                    stringResource(R.string.btn_stop_ble_scan)
                                } else {
                                    stringResource(R.string.btn_test_ble_scan)
                                }
                            )
                        }
                    }

                    if (bleReport.isScanning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.diag_scanning_now),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64B5F6)
                        )
                    }

                    if (bleReport.hasRun) {
                        Spacer(modifier = Modifier.height(8.dp))

                        if (bleReport.error != null) {
                            Text(
                                text = bleReport.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF8A65)
                            )
                        }

                        Text(
                            text = "${stringResource(R.string.diag_ble_packets)}: ${bleReport.rawPacketCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE0E0E0)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${stringResource(R.string.diag_shimano_found)}:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (bleReport.shimanoDevices.isNotEmpty()) {
                                Color(0xFF81C784)
                            } else {
                                Color(0xFFE0E0E0)
                            }
                        )
                        Text(
                            text = if (bleReport.shimanoDevices.isEmpty()) {
                                noneText
                            } else {
                                bleReport.shimanoDevices.joinToString("\n")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bleReport.shimanoDevices.isNotEmpty()) {
                                Color(0xFF81C784)
                            } else {
                                Color(0xFF9E9E9E)
                            }
                        )

                        if (bleReport.otherDevices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${stringResource(R.string.diag_other_ble)}:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFBDBDBD)
                            )
                            Text(
                                text = bleReport.otherDevices.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9E9E9E)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
