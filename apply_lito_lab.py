#!/usr/bin/env python3
"""Apply the FreeFCC Lito X1 Lab overlay to an upstream FreeFCC v1.5.5 checkout.

Usage:
    python3 apply_lito_lab.py /path/to/FreeFCC

The patch is intentionally conservative:
- no root / bootloader changes
- no arbitrary raw DUML command console
- read-only diagnostics by default
- the only active RF action reuses upstream profiles/fcc.json unchanged and
  adds matching-response/ACK accounting.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

BASE_VERSION = "1.5.5"
LAB_VERSION = "1.5.5.1-lito-lab"


def fail(msg: str) -> "NoReturn":
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(2)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected anchor exactly once, found {count}. Upstream may have changed.")
    return text.replace(old, new, 1)


def patch_file(path: Path, edits: list[tuple[str, str, str]]) -> None:
    if not path.exists():
        fail(f"missing expected file: {path}")
    text = path.read_text(encoding="utf-8")
    for old, new, label in edits:
        text = replace_once(text, old, new, label)
    path.write_text(text, encoding="utf-8")


def copy_overlay(src_root: Path, repo: Path) -> None:
    overlay = src_root / "overlay"
    if not overlay.is_dir():
        fail(f"overlay directory missing beside script: {overlay}")
    for src in overlay.rglob("*"):
        if not src.is_file():
            continue
        rel = src.relative_to(overlay)
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"overlay  {rel}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", type=Path, help="path to upstream FreeFCC checkout")
    args = ap.parse_args()
    repo = args.repo.expanduser().resolve()
    src_root = Path(__file__).resolve().parent

    if not (repo / "settings.gradle.kts").exists():
        fail(f"{repo} does not look like a FreeFCC checkout")

    build = repo / "app/build.gradle.kts"
    build_text = build.read_text(encoding="utf-8") if build.exists() else ""
    if f'versionName = "{BASE_VERSION}"' not in build_text and f'versionName = "{LAB_VERSION}"' not in build_text:
        fail(f"expected FreeFCC {BASE_VERSION}. Checkout tag/commit v{BASE_VERSION} (or upstream commit 597157b) first.")

    # Idempotence guard.
    if f'versionName = "{LAB_VERSION}"' in build_text:
        fail("Lito X1 Lab appears to be already applied to this checkout")

    # 1) Add standalone research helper + GitHub Actions workflow.
    copy_overlay(src_root, repo)

    # 2) Distinguish the build from upstream.
    patch_file(build, [
        ('versionCode = 22', 'versionCode = 23', 'versionCode'),
        (f'versionName = "{BASE_VERSION}"', f'versionName = "{LAB_VERSION}"', 'versionName'),
    ])

    # 3) Add state and ViewModel actions.
    vm = repo / "app/src/main/java/com/freefcc/app/FccViewModel.kt"
    state_old = '''    // Keepalive state\n    val isKeepaliveRunning: Boolean = false\n)'''
    state_new = '''    // Keepalive state\n    val isKeepaliveRunning: Boolean = false,\n    // Lito X1 Lab state\n    val isResearchBusy: Boolean = false,\n    val researchProgress: Float = 0f,\n    val researchReport: String = ""\n)'''

    research_methods = r'''    // --- Lito X1 Lab ---
    /** Builds a common, privacy-conscious header for diagnostics. */
    private fun researchHeader(): String = DumlResearch.buildHeader(
        app,
        _state.value.controllerModel,
        _state.value.aircraftSerial
    )

    /** Read-only scan of the known localhost DUML proxy ports. */
    fun researchScanPorts() {
        if (_state.value.isResearchBusy) return
        update { copy(isResearchBusy = true, researchProgress = 0f) }
        log("Lito Lab: scanning DUML ports (read-only)")
        runOnIO {
            val report = try {
                researchHeader() + DumlResearch.scanPorts()
            } catch (e: Exception) {
                researchHeader() + "DUML port scan error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            }
            update { copy(isResearchBusy = false, researchProgress = 1f, researchReport = report) }
            log("Lito Lab: DUML port scan complete")
        }
    }

    /**
     * Reads unsolicited DUML traffic for five seconds. No command is written.
     * If keepalive is running, its traffic may appear in the capture; that is
     * useful when diagnosing what FreeFCC itself is sending.
     */
    fun researchCaptureTelemetry() {
        if (_state.value.isResearchBusy) return
        update { copy(isResearchBusy = true, researchProgress = 0f) }
        log("Lito Lab: passive telemetry capture started (5s)")
        runOnIO {
            val report = try {
                researchHeader() + DumlResearch.capturePassive(5000).report()
            } catch (e: Exception) {
                researchHeader() + "Passive capture error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            }
            update { copy(isResearchBusy = false, researchProgress = 1f, researchReport = report) }
            log("Lito Lab: passive telemetry capture complete")
        }
    }

    /** Read-only inspection of the exact FCC profile bundled with this build. */
    fun researchAuditFccProfile() {
        if (_state.value.isResearchBusy) return
        update { copy(isResearchBusy = true, researchProgress = 0f) }
        log("Lito Lab: auditing FCC profile (read-only)")
        runOnIO {
            val report = try {
                researchHeader() + DumlResearch.auditFccProfile(app)
            } catch (e: Exception) {
                researchHeader() + "FCC profile audit error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            }
            update { copy(isResearchBusy = false, researchProgress = 1f, researchReport = report) }
            log("Lito Lab: FCC profile audit complete")
        }
    }

    /**
     * Active diagnostic: reuses upstream fcc.json unchanged, but calls
     * sendAndReceive() for each request and counts structurally valid matching
     * DUML responses. This is not an independent measurement of RF power/region.
     */
    fun researchApplyFccAckCheck() {
        if (_state.value.isResearchBusy) return
        if (!beginHardwareOp()) {
            log("Lito Lab: hardware busy — ACK diagnostic not started")
            return
        }
        update { copy(isResearchBusy = true, researchProgress = 0f) }
        log("Lito Lab: applying upstream FCC profile with ACK accounting")
        runOnIO {
            try {
                val result = DumlResearch.applyFccWithAckCheck(app) { progress ->
                    update { copy(researchProgress = progress.coerceIn(0f, 1f)) }
                }
                val report = researchHeader() + result.report()
                update { copy(isResearchBusy = false, researchProgress = 1f, researchReport = report) }
                log("Lito Lab: ACK diagnostic complete — ${result.validAcks}/${result.attempts} valid matching responses")
            } catch (e: Exception) {
                update {
                    copy(
                        isResearchBusy = false,
                        researchProgress = 0f,
                        researchReport = researchHeader() + "ACK diagnostic error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                    )
                }
                log("Lito Lab: ACK diagnostic error: ${e.message}")
            } finally {
                endHardwareOp()
            }
        }
    }

    /** Shares the current report as text; no storage permission is required. */
    fun shareResearchReport() {
        val report = _state.value.researchReport
        if (report.isBlank()) return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "FreeFCC Lito X1 Lab report")
                putExtra(android.content.Intent.EXTRA_TEXT, report)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = android.content.Intent.createChooser(intent, "Share Lito X1 Lab report").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        } catch (e: Exception) {
            log("Lito Lab: no app available to share report (${e.message})")
        }
    }

    fun clearResearchReport() {
        update { copy(researchReport = "", researchProgress = 0f) }
    }

'''

    patch_file(vm, [
        (state_old, state_new, 'AppState research fields'),
        (f'const val APP_VERSION = "{BASE_VERSION}"', 'const val APP_VERSION = "1.5.5.1"', 'APP_VERSION'),
        ('    // --- Updates ---\n', research_methods + '    // --- Updates ---\n', 'Lito Lab methods insertion'),
    ])

    # 4) Add a sixth Compose page.
    activity = repo / "app/src/main/java/com/freefcc/app/MainActivity.kt"
    research_page = r'''// ═══════════════════════════════════════════════════════════════════════
// Page 3: Lito X1 Lab
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun ResearchPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle("Lito X1 Lab", Icons.Outlined.BugReport)
        Spacer(Modifier.height(12.dp))
        BodyText(
            "Diagnostics for RC 2 / Lito X1. Read-only tools do not send DUML commands. " +
                "The ACK test is active: it sends exactly the FCC profile already bundled with FreeFCC and counts valid matching protocol responses.",
            TextGray
        )
        Spacer(Modifier.height(22.dp))

        GlowCard {
            Text("Read-only diagnostics", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            BodyText("Use these first. No root, bootloader change, or arbitrary command injection is used.", TextGray)
            Spacer(Modifier.height(16.dp))
            GlowButton("Scan DUML ports", Cyan, filled = false, enabled = !state.isResearchBusy) {
                viewModel.researchScanPorts()
            }
            Spacer(Modifier.height(10.dp))
            GlowButton("Capture passive telemetry — 5 s", Green, filled = false, enabled = !state.isResearchBusy) {
                viewModel.researchCaptureTelemetry()
            }
            Spacer(Modifier.height(10.dp))
            GlowButton("Audit installed FCC profile", Amber, filled = false, enabled = !state.isResearchBusy) {
                viewModel.researchAuditFccProfile()
            }
        }

        Spacer(Modifier.height(16.dp))
        GlowCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = Amber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Active FCC diagnostic", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            BodyText(
                "This performs the same RF-region modification as Enable FCC. ACKs prove only that matching DUML responses were received; they do not independently measure transmitter power or prove the final regulatory region.",
                TextGray
            )
            Spacer(Modifier.height(16.dp))
            GlowButton(
                "Apply FCC + count ACKs",
                Amber,
                filled = true,
                enabled = !state.isResearchBusy && !state.isHardwareBusy
            ) { viewModel.researchApplyFccAckCheck() }
        }

        if (state.isResearchBusy) {
            Spacer(Modifier.height(16.dp))
            GlowCard {
                ProgressDisplay(state.researchProgress, "Lito X1 Lab running…")
            }
        }

        if (state.researchReport.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            GlowCard {
                Text("Diagnostic report", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    state.researchReport,
                    color = TextGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(16.dp))
                GlowButton("Share report", Cyan, filled = false, enabled = !state.isResearchBusy) {
                    viewModel.shareResearchReport()
                }
                Spacer(Modifier.height(8.dp))
                GlowButton("Clear report", TextGray, filled = false, enabled = !state.isResearchBusy) {
                    viewModel.clearResearchReport()
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Reports may contain protocol payloads. Review before sharing publicly.",
                    color = TextDim,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

'''

    patch_file(activity, [
        ('val pagerState = rememberPagerState(initialPage = 0) { 5 }', 'val pagerState = rememberPagerState(initialPage = 0) { 6 }', 'pager count'),
        ('''                0 -> FccPage(state, viewModel)\n                1 -> InfoPage(state, viewModel)\n                2 -> LogPage(state)\n                3 -> UpdatePage(state, viewModel)\n                4 -> SupportPage()''',
         '''                0 -> FccPage(state, viewModel)\n                1 -> InfoPage(state, viewModel)\n                2 -> ResearchPage(state, viewModel)\n                3 -> LogPage(state)\n                4 -> UpdatePage(state, viewModel)\n                5 -> SupportPage()''', 'pager routes'),
        ('// Page 3: Log\n', research_page + '// Page 4: Log\n', 'ResearchPage insertion'),
        ('''        Triple("FCC", Icons.Filled.Wifi, Cyan),\n        Triple("Info", Icons.Filled.Info, Green),\n        Triple("Log", Icons.Filled.History, Amber),''',
         '''        Triple("FCC", Icons.Filled.Wifi, Cyan),\n        Triple("Info", Icons.Filled.Info, Green),\n        Triple("Lab", Icons.Filled.BugReport, Color(0xFF26C6DA)),\n        Triple("Log", Icons.Filled.History, Amber),''', 'bottom nav Lab tab'),
    ])

    print("\nSUCCESS: FreeFCC Lito X1 Lab patch applied.")
    print(f"Version: {LAB_VERSION}")
    print("Build debug APK with: ./gradlew assembleDebug")
    print("APK path: app/build/outputs/apk/debug/app-debug.apk")


if __name__ == "__main__":
    main()
