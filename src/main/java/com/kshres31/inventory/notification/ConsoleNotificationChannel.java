package com.kshres31.inventory.notification;

import com.kshres31.inventory.model.AlertNotification;

import java.io.PrintStream;

/** Development channel that prints each notification payload as one line. */
public final class ConsoleNotificationChannel implements NotificationChannel {
    private final PrintStream out;

    public ConsoleNotificationChannel(PrintStream out) {
        this.out = out;
    }

    @Override
    public void deliver(AlertNotification notification) {
        out.printf("[alert-notification %d] %s %s%n",
                notification.id(), notification.eventType(), notification.payloadJson());
    }

    @Override
    public String describe() {
        return "console";
    }
}
