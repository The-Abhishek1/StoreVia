package com.xcloak.storevia.data.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xcloak.storevia.MainActivity
import com.xcloak.storevia.R
import com.xcloak.storevia.core.money.Money

object AlertNotifier {
    const val CHANNEL_ID = "stock_alerts"
    private const val ID_EXPIRY = 1001
    private const val ID_LOW_STOCK = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.alert_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Posts one notification for expiry (expiring and expired together) and one for low stock. Same ids replace yesterday's. */
    @SuppressLint("MissingPermission")   // checked through canNotify()
    fun show(context: Context, plan: AlertPlan, warnDays: Int) {
        if (!canNotify(context)) return
        val nm = NotificationManagerCompat.from(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val res = context.resources

        if (plan.hasExpiryAlert) {
            val lines = buildList {
                if (plan.expiredBatches > 0) add(res.getQuantityString(R.plurals.alert_expired, plan.expiredBatches, plan.expiredBatches) +
                    " · " + context.getString(R.string.alert_cost_hint, Money(plan.expiredCostPaise).format()))
                if (plan.expiringBatches > 0) add(res.getQuantityString(R.plurals.alert_expiring, plan.expiringBatches, plan.expiringBatches, warnDays) +
                    " · " + context.getString(R.string.alert_cost_hint, Money(plan.expiringCostPaise).format()))
            }
            nm.notify(ID_EXPIRY, builder(context, open, context.getString(R.string.alert_title_expiry), lines).build())
        }
        if (plan.hasLowStockAlert) {
            val line = res.getQuantityString(R.plurals.alert_low_stock, plan.lowStockProducts, plan.lowStockProducts)
            nm.notify(ID_LOW_STOCK, builder(context, open, context.getString(R.string.alert_title_low_stock), listOf(line)).build())
        }
    }

    private fun builder(context: Context, open: PendingIntent, title: String, lines: List<String>) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
}
