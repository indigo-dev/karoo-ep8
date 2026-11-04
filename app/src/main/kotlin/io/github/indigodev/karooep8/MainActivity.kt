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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SetupStatusScreen(
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = ::requestBlePermissions
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = BlePermissions.hasScanPermissions(this)
    }

    private fun requestBlePermissions() {
        permissionLauncher.launch(BlePermissions.requiredPermissions())
    }
}

@Composable
private fun SetupStatusScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val badgeColor = if (permissionsGranted) Color(0xFF4CAF50) else Color(0xFFFFA000)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.extension_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

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
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_grant_permissions))
                }
            }
        }
    }
}
