package com.printease.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
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
        compose.onNodeWithText("A clear queue.\nA smoother day.").assertIsDisplayed()
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
}
