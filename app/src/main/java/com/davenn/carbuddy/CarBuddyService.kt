package com.davenn.carbuddy

import android.content.Intent
import android.view.KeyEvent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat

/** Entry point Android Auto binds to. */
class CarBuddyService : CarAppService() {
    // carBuddy is sideloaded and never published, so it accepts any host,
    // including the Desktop Head Unit used for testing on a computer.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarBuddySession()
}

class CarBuddySession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val display = CarDisplay(carContext)
        lifecycle.addObserver(display)
        display.attach()
        return DisplayScreen(carContext, display)
    }
}

/**
 * The car's main screen: a navigation template whose "map" is the virtual
 * display. The buttons float over it.
 */
class DisplayScreen(carContext: CarContext, private val display: CarDisplay) : Screen(carContext) {

    init {
        display.onChange = { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val running = display.displayId >= 0

        val actions = ActionStrip.Builder()
        if (running) {
            actions.addAction(iconAction(R.drawable.ic_apps) {
                screenManager.push(AppsScreen(carContext, display))
            })
            actions.addAction(iconAction(R.drawable.ic_back) { display.key(KeyEvent.KEYCODE_BACK) })
            actions.addAction(iconAction(R.drawable.ic_home) { display.key(KeyEvent.KEYCODE_HOME) })
        } else {
            actions.addAction(iconAction(R.drawable.ic_refresh) { display.retry() })
        }

        val builder = NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            // PAN puts the car into pan mode, which is how hosts that do not send
            // drags directly are told to forward them (as onScroll).
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }

        val status = display.status
        if (!running || status != "Running") {
            builder.setNavigationInfo(
                MessageInfo.Builder("carBuddy").setText(status).build()
            )
        }
        return builder.build()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}

/** Picker for which app to open on the car display. */
class AppsScreen(carContext: CarContext, private val display: CarDisplay) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val hostLimit = if (carContext.carAppApiLevel >= 2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else 6
        // Icons travel to the host as raw bitmaps in one Binder call, which has
        // a ~1 MB ceiling, so the list is capped regardless of what the host allows.
        val limit = minOf(hostLimit, MAX_APPS)

        val items = ItemList.Builder()
        for (app in Apps.list(carContext).take(limit)) {
            val row = Row.Builder().setTitle(app.label)
            Apps.icon(carContext, app, ICON_PX)?.let {
                row.setImage(CarIcon.Builder(IconCompat.createWithBitmap(it)).build())
            }
            row.setOnClickListener {
                val error = display.launch(app)
                if (error != null) {
                    CarToast.makeText(carContext, error, CarToast.LENGTH_LONG).show()
                }
                screenManager.pop()
            }
            items.addItem(row.build())
        }

        return ListTemplate.Builder()
            .setTitle("Open on car screen")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private companion object {
        const val MAX_APPS = 25
        const val ICON_PX = 48
    }
}
