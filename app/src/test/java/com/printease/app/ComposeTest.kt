package com.printease.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeTest {
    @get:Rule val compose = createComposeRule()
    private val student = Session("demo-student", "Alex Student", "student@demo.local", false)
    private val orders =
        listOf(
            PrintOrder(
                "sample",
                student.uid,
                student.name,
                "Research proposal.pdf",
                "",
                PrintOptions(pages = 12, copies = 2),
                status = OrderStatus.Printing,
                queue = 1,
                queueDay = "2026-11-14",
            )
        )

    @Test
    fun studentCanStartRequestAndDashboardRenders() {
        var clicked = false
        compose.setContent {
            PrintEaseTheme(dark = false) {
                Dashboard(
                    ShopState(student, orders),
                    demo = true,
                    overview = true,
                    onNew = { clicked = true },
                    onOrder = {},
                )
            }
        }
        compose.onNodeWithText("Hello, Alex.").assertIsDisplayed()
        compose.onNodeWithText("New print request").performClick()
        assertTrue(clicked)
        File("build/screenshots").mkdirs()
        compose.onRoot().captureRoboImage("build/screenshots/student-dashboard.png")
    }

    @Test
    fun searchHasUsefulEmptyState() {
        compose.setContent {
            PrintEaseTheme { Dashboard(ShopState(student, orders), true, false, {}, {}) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("nonexistent.pdf")
        compose.onNodeWithText("No matching requests").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun adminReviewIncludesAcceptAndReject() {
        val pending =
            orders.first().copy(status = OrderStatus.Pending, queue = null, queueDay = null)
        compose.setContent {
            PrintEaseTheme { OrderDetail(pending, true, false, false, { _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("Accept & assign queue number").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reject request").performScrollTo().performClick()
        compose.onNodeWithText("Confirm rejection").performScrollTo().assertIsNotEnabled()
        compose
            .onNode(hasSetTextAction())
            .performScrollTo()
            .performTextInput("Please resend the document.")
        compose.onNodeWithText("Confirm rejection").performScrollTo().assertIsEnabled()
    }

    @Test
    fun emptyDraftCannotBeSubmitted() {
        compose.setContent {
            PrintEaseTheme { RequestContent(null, PrintOptions(), false, 0f, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Review request").assertIsNotEnabled()
    }

    @Test
    fun darkAndLargeFontAccountRemainsReadable() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)
            ) {
                PrintEaseTheme(dark = true) { AccountContent(student, true, false, {}, {}) }
            }
        }
        compose.onNodeWithText("Account & print guide").assertIsDisplayed()
        compose.onNodeWithText("Switch to administrator").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/account-dark.png")
    }

    @Test
    fun fullAppSupportsRoleSwitching() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application
            .getSharedPreferences("printease-demo-v2", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val model = PrintViewModel(application, SavedStateHandle())
        model.demoLogin(false)
        compose.setContent { PrintEaseTheme(dark = false) { PrintEase(model) } }
        compose.onNodeWithText("Hello, Alex.").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/full-student.png")
        compose.onNodeWithText("Account", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Switch to administrator").performScrollTo().performClick()
        compose.onAllNodesWithText("Print queue").onFirst().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/full-admin.png")
        model.signOut()
    }

    @Test
    fun googleButtonWorksWithoutTypingEmailOrPassword() {
        var clicks = 0
        compose.setContent {
            PrintEaseTheme(dark = false) {
                AuthContent(false, false, null, {}, { _, _, _ -> }, {}, onGoogle = { clicks++ })
            }
        }
        compose
            .onNodeWithText("Continue with Google")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("Welcome back").performScrollTo()
        compose.onRoot().captureRoboImage("build/screenshots/google-sign-in.png")
        compose.onNodeWithText("New here? Create an account").performScrollTo().performClick()
        compose.onNodeWithText("Continue with Google").performScrollTo().performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun googleButtonIsDisabledDuringAuthentication() {
        compose.setContent {
            PrintEaseTheme { AuthContent(false, true, null, {}, { _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("Continue with Google").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun demoDoesNotOfferRealGoogleSignIn() {
        compose.setContent {
            PrintEaseTheme { AuthContent(true, false, null, {}, { _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
    }

    @Test
    fun pausedShopDisablesNewRequestAndSettingsFitLargeFonts() {
        compose.setContent {
            PrintEaseTheme {
                Dashboard(
                    ShopState(student, orders, settings = ShopSettings(acceptingOrders = false)),
                    true,
                    true,
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Requests paused").assertIsNotEnabled()
        compose.onNodeWithText("Nano Print Alangilan").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectedRangesChangeTheQuoteAndInvalidInputBlocksReview() {
        var options by mutableStateOf(PrintOptions(pages = 10))
        compose.setContent {
            PrintEaseTheme(dark = false) {
                RequestContent(
                    PdfFile("draft-path", "Ten pages.pdf", 10, 100, "hash"),
                    options,
                    false,
                    0f,
                    {},
                    { options = it },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Pages to print").performScrollTo().performTextInput("1-3, 5")
        compose.onNodeWithText("₱8.00").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/request-selected-pages.png")
        compose.onNodeWithText("Pages to print").performScrollTo().performTextReplacement("11")
        compose.onNodeWithText("Review request").assertIsNotEnabled()
    }

    @Test
    fun printPreviewScrollsToLaterPages() {
        val bitmap = Bitmap.createBitmap(420, 594, Bitmap.Config.ARGB_8888)
        val renderPage: suspend (Int) -> Pair<Bitmap, Boolean> = { bitmap to false }
        compose.setContent {
            PrintEaseTheme {
                ScrollablePreviewPages(
                    3,
                    rememberLazyListState(),
                    Modifier.fillMaxSize(),
                    renderPage,
                )
            }
        }
        compose.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithContentDescription("Page 3 of 3").assertIsDisplayed()
    }

    @Test
    fun shopSettingsSaveWholePesoRatesAndKeepRetentionOff() {
        var result: ShopSettings? = null
        compose.setContent {
            PrintEaseTheme(dark = false) {
                ShopSettingsContent(ShopSettings(), false) { result = it }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/shop-settings.png")
        compose
            .onNodeWithText("Black & white · A4 / Letter")
            .performScrollTo()
            .performTextReplacement("4")
        compose.onNodeWithText("Save shop details").performScrollTo().performClick()
        assertEquals(4L, result!!.rates.bw)
        assertEquals(0, result!!.retentionDays)
        assertEquals("Nano Print Alangilan", result!!.name)
    }

    @Test
    fun collectionHandoverRequiresTheStudentCode() {
        val ready = orders.first().copy(status = OrderStatus.Ready)
        var received = ""
        compose.setContent {
            PrintEaseTheme(dark = true) {
                OrderDetail(ready, true, false, false, { _, _, note -> received = note }, {})
            }
        }
        compose.onNodeWithText("Verify collection").performScrollTo().performClick()
        compose.onNodeWithText("Confirm collection").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("wrong")
        compose.onNodeWithText("Confirm collection").performScrollTo().assertIsNotEnabled()
        compose
            .onNode(hasSetTextAction())
            .performScrollTo()
            .performTextReplacement(collectionCode(ready.id))
        compose
            .onNodeWithText("Confirm collection")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(collectionCode(ready.id), received)
    }

    @Test
    fun pickupSummaryShowsStudentCodeAndEstimateAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)
            ) {
                PrintEaseTheme(dark = true) {
                    PickupSummary(
                        orders.first().copy(expectedReadyAt = System.currentTimeMillis() + 600000),
                        false,
                        false,
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Your collection code").assertIsDisplayed()
        compose.onNodeWithText(collectionCode(orders.first().id)).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/pickup-summary-dark.png")
    }
}
