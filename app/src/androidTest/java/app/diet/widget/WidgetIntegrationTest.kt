package app.diet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.diet.R
import app.diet.data.MicroNutrients
import app.diet.data.Operation
import app.diet.integration.IntegrationDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WidgetIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun oneDayReadIgnoresVisiblePastDateAndTracksGoalCompletion(): Unit = runBlocking {
        val fixture = IntegrationDatabase(context)
        try {
            fixture.initialize()
            val repo = fixture.repository
            val day = LocalDate.parse("2026-11-15")
            repo.setVisibleDate("2026-11-10")
            repo.addQuickFood(day.toString(), repo.state.value.meals.first().id, "Synthetic widget portion", MicroNutrients(250_500_000, 0, 0, 0), Operation())
            repo.saveGoal(day.toString(), MicroNutrients(1800_000_000, 0, 0, 0), null, repo.state.value.goalTimelineRevision, Operation())
            val partial = WidgetSummary.from(repo.pageReportDays(day, day, 0, 1).days.single())
            assertEquals("250,5 ккал", partial.calories)
            assertEquals("Цель: 1800,0 ккал", partial.goal)
            assertEquals("День заполнен частично", partial.status)
            assertEquals("2026-11-10", repo.state.value.visibleDate)
            repo.completeDay(day.toString(), true, false, Operation())
            val complete = WidgetSummary.from(repo.pageReportDays(day, day, 0, 1).days.single())
            assertEquals("День заполнен", complete.status)
            assertEquals("250,5 ккал", complete.calories)
        } finally { fixture.close() }
    }

    @Test fun remoteViewsHaveLiveSummaryAccessibleActionsAndPrivateProvider(): Unit = runBlocking {
        val fixture = IntegrationDatabase(context)
        try {
            fixture.initialize()
            val controller = DietWidgetController(context, fixture.repository, fixture.scope)
            val day = LocalDate.parse("2026-11-15")
            val summary = WidgetSummary.from(fixture.repository.pageReportDays(day, day, 0, 1).days.single())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                for (scale in listOf(1f, 2f)) {
                    val sizedContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                    val host = FrameLayout(sizedContext)
                    val view = controller.render(-2140021, summary).apply(sizedContext, host)
                    val width = (250 * sizedContext.resources.displayMetrics.density).toInt()
                    val height = (230 * sizedContext.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    assertEquals("— ккал", view.findViewById<TextView>(R.id.widget_value).text.toString())
                    assertEquals("15 ноября", view.findViewById<TextView>(R.id.widget_date).text.toString())
                    for (id in listOf(R.id.widget_food, R.id.widget_weight, R.id.widget_sleep, R.id.widget_refresh)) {
                        val action = view.findViewById<TextView>(id)
                        assertTrue(action.hasOnClickListeners())
                        assertTrue(action.height >= (48 * sizedContext.resources.displayMetrics.density).toInt())
                        assertFalse(action.contentDescription.isNullOrBlank())
                        val xy = IntArray(2); action.getLocationInWindow(xy)
                        assertTrue("Action clipped at fontScale=$scale", xy[1] + action.height <= height)
                    }
                    for (id in listOf(R.id.widget_value, R.id.widget_goal, R.id.widget_status)) {
                        val text = view.findViewById<TextView>(id)
                        assertTrue("No space for widget text at fontScale=$scale", text.height > 0)
                        for (line in 0 until text.layout.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                    }
                }
            }
            val component = ComponentName(context, DietWidgetProvider::class.java)
            val receiver = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
            assertFalse(receiver.exported)
            assertFalse(receiver.directBootAware)
            val provider = AppWidgetManager.getInstance(context).installedProviders.first { it.provider == component }
            assertTrue(provider.widgetCategory.and(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0)
            if (Build.VERSION.SDK_INT >= 36) assertTrue(provider.widgetCategory.and(AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD) != 0)
            val food = DietWidgetController.openIntent(context, -2140021, "food")
            val weight = DietWidgetController.openIntent(context, -2140021, "weight")
            assertNotEquals(food, weight)
            if (Build.VERSION.SDK_INT >= 31) { assertTrue(food.isImmutable); assertTrue(weight.isImmutable) }
            food.cancel(); weight.cancel()
        } finally { fixture.close() }
    }
}
