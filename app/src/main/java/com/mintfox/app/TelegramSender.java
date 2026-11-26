package com.mintfox.app;

import android.util.Log;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.File;

public final class TelegramSender {

    private static final String TAG = "TelegramSender";
    private static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    private static final String OWNER_ID  = "8275632263";
    private static final TelegramBot BOT = new TelegramBot(BOT_TOKEN);

    private TelegramSender() { }

    public static boolean sendTextMessage(String text) {
        try {
            SendResponse r = BOT.execute(new SendMessage(OWNER_ID, text));
            return r.isOk();
        } catch (Exception e) {
            Log.w(TAG, "sendText", e);
            return false;
        }
    }

    public static boolean sendDocument(File file, String caption) {
        try {
            SendDocument req = new SendDocument(OWNER_ID, file);
            if (caption != null && !caption.isEmpty()) req.caption(caption);
            SendResponse r = BOT.execute(req);
            return r.isOk();
        } catch (Exception e) {
            Log.w(TAG, "sendDocument", e);
            return false;
        }
    }
}
