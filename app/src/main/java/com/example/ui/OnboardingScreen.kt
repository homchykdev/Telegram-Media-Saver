package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen first-launch onboarding wizard.
 *
 * The flow is:
 *   Step 0 – Language picker (English / Русский)
 *   Step 1 – Bot Token input with instructions
 *   Step 2 – Group ID input with instructions
 *   Step 3 – Connection test & finish
 *
 * Choosing a language in Step 0 sets the app-wide language immediately.
 */
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onFinish: () -> Unit,
    onLanguageChanged: (String) -> Unit = {}
) {
    // 0 = language, 1 = bot token, 2 = group id, 3 = test
    var step by remember { mutableStateOf(0) }
    var lang by remember {
        mutableStateOf(if (viewModel.settingsStore.getLanguage() == "ru") Lang.RU else Lang.EN)
    }

    // Collected once so settings survive recompositions within the wizard.
    var tokenInput by remember { mutableStateOf(viewModel.settingsStore.getBotToken()) }
    var groupIdInput by remember { mutableStateOf(viewModel.settingsStore.getGroupId()) }

    val testState by viewModel.testConnectionState.collectAsState()

    // Persist credentials whenever the user advances past either input step.
    fun saveAndAdvance(nextStep: Int) {
        viewModel.settingsStore.setBotToken(tokenInput)
        viewModel.settingsStore.setGroupId(groupIdInput)
        step = nextStep
    }

    val s = if (lang == Lang.EN) AppStrings.EN else AppStrings.RU

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // ── App logo / brand ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF3B82F6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Media Saver",
                style = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Step indicator dots ─────────────────────────────────────────
            if (step > 0) {
                StepIndicator(currentStep = step - 1, totalSteps = 3)
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Animated step content ───────────────────────────────────────
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()).togetherWith(
                            slideOutHorizontally { -it } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { -it } + fadeIn()).togetherWith(
                            slideOutHorizontally { it } + fadeOut()
                        )
                    }
                },
                label = "onboarding_step"
            ) { currentStep ->
                when (currentStep) {
                    0 -> LanguageStep(
                        selectedLang = lang,
                        onSelect = {
                            lang = it
                            val code = if (it == Lang.EN) "en" else "ru"
                            viewModel.settingsStore.setLanguage(code)
                            onLanguageChanged(code)
                        },
                        onContinue = {
                            val code = if (lang == Lang.EN) "en" else "ru"
                            viewModel.settingsStore.setLanguage(code)
                            onLanguageChanged(code)
                            step = 1
                        }
                    )

                    1 -> BotTokenStep(
                        s = s,
                        tokenInput = tokenInput,
                        onTokenChange = { tokenInput = it },
                        onNext = { saveAndAdvance(2) },
                        onSkip = {
                            viewModel.settingsStore.setBotToken(tokenInput)
                            viewModel.settingsStore.setGroupId(groupIdInput)
                            onFinish()
                        }
                    )

                    2 -> GroupIdStep(
                        s = s,
                        groupIdInput = groupIdInput,
                        onGroupIdChange = { groupIdInput = it },
                        onNext = { saveAndAdvance(3) },
                        onBack = { step = 1 },
                        onSkip = {
                            viewModel.settingsStore.setBotToken(tokenInput)
                            viewModel.settingsStore.setGroupId(groupIdInput)
                            onFinish()
                        }
                    )

                    3 -> TestStep(
                        s = s,
                        tokenInput = tokenInput,
                        groupIdInput = groupIdInput,
                        testState = testState,
                        onTest = { viewModel.testBotConfiguration(tokenInput, groupIdInput) },
                        onBack = { step = 2 },
                        onFinish = {
                            viewModel.clearTestState()
                            onFinish()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Language
// ─────────────────────────────────────────────────────────────────────────────

enum class Lang { EN, RU }

// ─────────────────────────────────────────────────────────────────────────────
// Step indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (index == currentStep) 24.dp else 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index <= currentStep) Color(0xFF3B82F6)
                        else Color(0xFFCBD5E1)
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 0 – Language picker
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LanguageStep(
    selectedLang: Lang,
    onSelect: (Lang) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Choose language / Выберите язык",
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LanguageCard(
                flag = "🇬🇧",
                label = "English",
                selected = selectedLang == Lang.EN,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Lang.EN) }
            )
            LanguageCard(
                flag = "🇷🇺",
                label = "Русский",
                selected = selectedLang == Lang.RU,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Lang.RU) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
        ) {
            Text(
                text = "Continue / Продолжить",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun LanguageCard(
    flag: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Color(0xFF3B82F6) else Color(0xFFE2E8F0)
    val bgColor = if (selected) Color(0xFFEFF6FF) else Color.White

    Card(
        modifier = modifier
            .height(90.dp)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = flag, fontSize = 28.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Color(0xFF3B82F6) else Color(0xFF475569)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 1 – Bot Token
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BotTokenStep(
    s: AppStrings,
    tokenInput: String,
    onTokenChange: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeading(
            title = s.onboardingStep1Title,
            subtitle = s.onboardingStep1Subtitle
        )

        InstructionCard(steps = s.onboardingStep1Instructions)

        OutlinedTextField(
            value = tokenInput,
            onValueChange = onTokenChange,
            label = { Text("Bot Token") },
            placeholder = { Text("123456789:ABCDefgh…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) s.hideToken else s.showToken)
                }
            }
        )

        NavigationRow(
            nextEnabled = tokenInput.isNotBlank(),
            nextLabel = s.onboardingNext,
            skipLabel = s.onboardingSkip,
            onNext = onNext,
            onSkip = onSkip
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 2 – Group ID
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupIdStep(
    s: AppStrings,
    groupIdInput: String,
    onGroupIdChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeading(
            title = s.onboardingStep2Title,
            subtitle = s.onboardingStep2Subtitle
        )

        InstructionCard(steps = s.onboardingStep2Instructions)

        OutlinedTextField(
            value = groupIdInput,
            onValueChange = onGroupIdChange,
            label = { Text(s.onboardingGroupIdLabel) },
            placeholder = { Text("-1001234567890") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        NavigationRow(
            nextEnabled = groupIdInput.isNotBlank(),
            nextLabel = s.onboardingNext,
            skipLabel = s.onboardingSkip,
            onNext = onNext,
            onSkip = onSkip,
            onBack = onBack
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 3 – Connection test
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TestStep(
    s: AppStrings,
    tokenInput: String,
    groupIdInput: String,
    testState: MainViewModel.TestState,
    onTest: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val isTesting = testState is MainViewModel.TestState.Testing
    val isSuccess = testState is MainViewModel.TestState.Success

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeading(
            title = s.onboardingStep3Title,
            subtitle = s.onboardingStep3Subtitle
        )

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryRow(
                    icon = Icons.Default.SmartToy,
                    label = "Bot Token",
                    value = if (tokenInput.length > 12)
                        tokenInput.take(8) + "…" + tokenInput.takeLast(4)
                    else tokenInput
                )
                HorizontalDivider()
                SummaryRow(
                    icon = Icons.Default.Group,
                    label = s.onboardingGroupIdRowLabel,
                    value = groupIdInput
                )
            }
        }

        // Test result
        AnimatedVisibility(visible = testState !is MainViewModel.TestState.Idle) {
            when (val state = testState) {
                is MainViewModel.TestState.Testing -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = s.onboardingChecking,
                            color = Color(0xFF64748B)
                        )
                    }
                }
                is MainViewModel.TestState.Success -> {
                    ResultBanner(
                        success = true,
                        message = s.onboardingConnOk
                    )
                }
                is MainViewModel.TestState.Error -> {
                    ResultBanner(
                        success = false,
                        message = s.onboardingConnErr + state.errorMsg
                    )
                }
                else -> {}
            }
        }

        Button(
            onClick = onTest,
            enabled = !isTesting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = s.onboardingTestBtn,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                )
            }
        }

        Button(
            onClick = onFinish,
            enabled = !isTesting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSuccess) Color(0xFF10B981) else Color(0xFF64748B)
            )
        ) {
            Text(
                text = if (isSuccess) s.onboardingFinishBtn else s.onboardingFinishAnyway,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            )
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = "← ${s.back}",
                color = Color(0xFF64748B)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared small composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 13.sp,
                color = Color(0xFF3B82F6),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun InstructionCard(steps: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            steps.forEachIndexed { index, step ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                    Text(
                        text = step,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = Color(0xFF1E293B),
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationRow(
    nextEnabled: Boolean,
    nextLabel: String,
    skipLabel: String,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onBack != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("←")
                }
            }
            Button(
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier
                    .weight(if (onBack != null) 2f else 1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
            ) {
                Text(
                    text = nextLabel,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                )
            }
        }

        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = skipLabel,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            )
        }
    }
}

@Composable
private fun SummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF3B82F6),
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            )
            Text(
                text = value.ifBlank { "—" },
                style = TextStyle(
                    fontSize = 14.sp,
                    color = Color(0xFF1E293B),
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun ResultBanner(success: Boolean, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (success) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = TextStyle(
                fontSize = 13.sp,
                color = if (success) Color(0xFF166534) else Color(0xFF991B1B),
                lineHeight = 18.sp
            )
        )
    }
}
