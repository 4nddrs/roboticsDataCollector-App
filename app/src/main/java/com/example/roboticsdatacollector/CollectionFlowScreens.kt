package com.example.roboticsdatacollector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SessionSetupOverlay(
    config: SessionConfig,
    onConfigChange: (SessionConfig) -> Unit,
    onContinue: () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF90CAF9),
            onPrimary = Color.Black,
            surface = Color(0xFF1C1C1E),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(0xFF8E8E93)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xE6111111)) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Start Session", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(
                        "Experiment, participant, and task are stored in metadata.json.",
                        color = Color(0xFFBDBDBD),
                        fontSize = 13.sp
                    )
                    SetupField("Experiment", config.experiment) {
                        onConfigChange(config.copy(experiment = it))
                    }
                    SetupField("Participant ID", config.participantId) {
                        onConfigChange(config.copy(participantId = it))
                    }
                    SetupField("Environment", config.environment) {
                        onConfigChange(config.copy(environment = it))
                    }
                    SetupField("Task / Activity", config.task) {
                        onConfigChange(config.copy(task = it))
                    }
                    SetupField("Skill (optional)", config.skill) {
                        onConfigChange(config.copy(skill = it))
                    }
                    SetupField("Narration note (optional)", config.narration) {
                        onConfigChange(config.copy(narration = it))
                    }
                    Text("Recording profile", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordingProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = config.profile == profile,
                                onClick = { onConfigChange(config.copy(profile = profile)) },
                                label = { Text(profile.label, fontSize = 12.sp, color = Color.White) }
                            )
                        }
                    }
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        enabled = config.experiment.isNotBlank() && config.participantId.isNotBlank()
                    ) {
                        Text("Continue to pre-flight")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, color = Color(0xFFBDBDBD)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Color.White,
            cursorColor = Color.White,
            focusedLabelColor = Color(0xFF90CAF9),
            unfocusedLabelColor = Color(0xFFBDBDBD),
            focusedBorderColor = Color(0xFF90CAF9),
            unfocusedBorderColor = Color(0xFF8E8E93),
            focusedContainerColor = Color(0xFF2A2A2C),
            unfocusedContainerColor = Color(0xFF2A2A2C),
            focusedPlaceholderColor = Color(0xFF9E9E9E),
            unfocusedPlaceholderColor = Color(0xFF9E9E9E)
        )
    )
}

@Composable
fun MountingCheckOverlay(
    handState: HandVisibilityState,
    workspaceVisible: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onOverride: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC111111), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Mounting check", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                if (!workspaceVisible) {
                    "Tilt phone slightly downward — workspace not visible"
                } else if (handState == HandVisibilityState.NONE) {
                    "Move camera lower — hands are not visible"
                } else {
                    "Ready: hands and workspace in view. Mount the phone and start."
                },
                color = Color.White,
                fontSize = 13.sp
            )
            Text(
                "Hands: ${handState.name}  ·  Workspace: ${if (workspaceVisible) "GOOD" else "BAD"}",
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp
            )
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Text("START COLLECTION")
            }
            OutlinedButton(onClick = onOverride, modifier = Modifier.fillMaxWidth()) {
                Text("Start anyway (logged override)")
            }
        }
    }
}

@Composable
fun SessionReportOverlay(
    report: SessionReport,
    onDone: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xE6111111)) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Session complete", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(report.sessionId, color = Color(0xFFBDBDBD), fontSize = 13.sp)
                ReportRow("Duration", "%.1f s".format(report.durationSeconds))
                ReportRow("Status", report.status)
                ReportRow("Overall", report.overall.name)
                ReportRow("Video", if (report.videoSaved) "Saved" else "Missing")
                ReportRow("IMU", if (report.imuSaved) "Saved" else "Missing")
                ReportRow("Audio", if (report.audioSaved) "In video track" else "Missing")
                ReportRow("Hands visible", "%.1f%%".format(report.handVisibilityPercent))
                ReportRow("Workspace visible", "%.1f%%".format(report.workspaceVisibilityPercent))
                ReportRow("Camera quality", "%.1f%%".format(report.cameraQualityPercent))
                ReportRow("Dropped frames", report.droppedFrames.toString())
                ReportRow("IMU gaps", report.sensorGaps.toString())
                ReportRow("Storage used", "%.1f MB".format(report.storageBytes / (1024.0 * 1024.0)))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFBDBDBD), fontSize = 14.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun RecoveryDialogContent(
    sessionId: String,
    onRecover: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Unfinished session found: $sessionId. Recover keeps files and marks INTERRUPTED_SYSTEM.",
            color = Color.White,
            fontSize = 14.sp
        )
        Button(onClick = onRecover, modifier = Modifier.fillMaxWidth()) { Text("Recover") }
        OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
    }
}
