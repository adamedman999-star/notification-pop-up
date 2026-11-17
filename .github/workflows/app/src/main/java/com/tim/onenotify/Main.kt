package com.tim.onenotify

import android.app.Activity
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val s = if (NotifyService.instance != null) "подключён" else "НЕ подключён"
            status?.text = "Сервис: $s\nПолучено: ${NotifyService.count}\n${NotifyService.log}"
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "OneNotify\n\n1. Дай доступ к уведомлениям\n2. Разреши показ поверх других окон\n3. Нажми «Тест»"
            textSize = 16f
        })
        fun btn(label: String, action: () -> Unit) = root.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
        btn("Доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("Показ поверх других окон") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btn("Тест") { NotifyService.instance?.demo() }
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        root.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }
}

class NotifyService : NotificationListenerService() {

    companion object {
        var instance: NotifyService? = null
        var count = 0
        var log = "ещё ничего не приходило"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var current: View? = null
    private val autoHide = Runnable { dismiss() }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        try {
            requestRebind(ComponentName(this, NotifyService::class.java))
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val pkg = sbn.packageName
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        count++
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            log = "#$count $pkg: пропущено (сводка группы)"
            return
        }

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) {
            log = "#$count $pkg: пропущено (пустой текст)"
            return
        }
        log = "#$count $pkg: показываю"

        handler.post {
            val appIcon = try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
            val avatar = try {
                n.getLargeIcon()?.loadDrawable(this)
            } catch (e: Exception) {
                null
            }
            show(title, text, avatar, appIcon) {
                try {
                    n.contentIntent?.send()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun demo() {
        handler.post {
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }
            show("OneNotify", "Так будут выглядеть уведомления", null, appIcon, null)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun show(
        title: String,
        text: String,
        avatar: Drawable?,
        appIcon: Drawable?,
        onTap: (() -> Unit)?
    ) {
        if (!Settings.canDrawOverlays(this)) {
            log += " | нет разрешения поверх окон"
            return
        }
        removeNow()

        val fg = 0xFF111111.toInt()
        val fg2 = 0xFF555555.toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), 0x22000000)
            }
        }

        val photo = avatar ?: appIcon
        if (photo != null) {
            val box = FrameLayout(this)
            val photoView = ImageView(this).apply {
                setImageDrawable(photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (avatar != null) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    clipToOutline = true
                }
            }
              box.addView(photoView, FrameLayout.LayoutParams(dp(40), dp(40)))
            if (avatar != null && appIcon != null) {
                box.addView(
                    ImageView(this).apply { setImageDrawable(appIcon) },
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END)
                )
            }
         card.addView(box, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (title.isNotBlank()) {
            col.addView(TextView(this).apply {
                this.text = title
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (text.isNotBlank()) {
            col.addView(TextView(this).apply {
                this.text = text
                setTextColor(fg2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val container = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        var downY = 0f
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (dy < -dp(20)) {
                        dismiss()
                    } else if (Math.abs(dy) < dp(10)) {
                        dismiss()
                        onTap?.invoke()
                    }
                    true
                }
                else -> true
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(4)
        }

        try {
            wm.addView(container, lp)
        } catch (e: Exception) {
            log += " | ошибка окна: ${e.message}"
            return
        }
        current = container

        handler.postDelayed({
            if (current === container) {
                card.alpha = 1f
                card.scaleX = 1f
                col.alpha = 1f
            }
        }, 700)

        // Раскрытие в стороны от центра
        card.alpha = 0f
        card.scaleX = 0.3f
        col.alpha = 0f
        card.post {
            card.pivotX = card.width / 2f
            card.animate().scaleX(1f).alpha(1f).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
            col.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
        }
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 5000)
    }

    private fun dismiss() {
        val v = current ?: return
        current = null
        handler.removeCallbacks(autoHide)
        val card = (v as FrameLayout).getChildAt(0)
        card.pivotX = card.width / 2f
        card.animate().scaleX(0.3f).alpha(0f).setDuration(200).withEndAction {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun removeNow() {
        val v = current ?: return
        current = null
        handler.removeCallbacks(autoHide)
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }
}
