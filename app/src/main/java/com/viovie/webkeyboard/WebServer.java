package com.viovie.webkeyboard;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.support.annotation.RawRes;
import android.util.Log;
import android.view.inputmethod.ExtractedTextRequest;

import com.viovie.webkeyboard.activity.AlertActivity;
import com.viovie.webkeyboard.service.RemoteKeyboardService;
import com.viovie.webkeyboard.task.CtrlInputAction;
import com.viovie.webkeyboard.task.TextInputAction;
import com.viovie.webkeyboard.util.ConnectUtil;
import com.viovie.webkeyboard.util.Logger;

import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;


public class WebServer extends NanoHTTPD {
    private static Logger logger = Logger.getInstance(WebServer.class);

    private RemoteKeyboardService service;

    public WebServer(RemoteKeyboardService service, int port) {
        super(port);
        this.service = service;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        Map<String, String> header = session.getHeaders();
        String uri = session.getUri();
       Log.d( "qwh",uri);
        // Return file
        if (uri.equals("/script.js")) {
            return newFixedLengthResponse(loadLocalFile(R.raw.script));
        } else if (uri.equals("/msgpack_min.js")) {
            return newFixedLengthResponse(loadLocalFile(R.raw.msgpack_min));
        } else if (uri.equals("/style.css")) {
            return newFixedLengthResponse(Response.Status.OK, "text/css", loadLocalFile(R.raw.style));
        } else if(uri.equals("/favicon.ico"))return newChunkedResponse(Response.Status.OK, "image/ico",loadLocalFile(R.raw.favicon,"ico"));


        final String ip = header.get("http-client-ip");

        // Check verify
        if (uri.equals("/check") && session.getMethod() == Method.POST) {
            return newFixedLengthResponse(
                    ConnectUtil.getInstance(service).isConnect(ip) ?
                    Response.Status.OK : Response.Status.NOT_ACCEPTABLE,
                    "text/json", "");
        }

        // Verify connect
        if (!ConnectUtil.getInstance(service).isConnect(ip)) {
            service.handler.post(new Runnable() {
                @SuppressLint("NewApi")
				@Override
                public void run() {
                    Intent intent = new Intent(service, AlertActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    Bundle bundle = new Bundle();
                    bundle.putString(AlertActivity.INTENT_PARAM_IP, ip);
                    intent.putExtras(bundle);
                    service.startActivity(intent);
                }
            });

            // Reload page
            return newFixedLengthResponse(loadLocalFile(R.raw.reload));
        }

        if (uri.equals("/key")) {
            if (session.getMethod() == Method.POST) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(session.getInputStream());
                JSONObject jsonObj = null;
                try {
                    jsonObj = new JSONObject(unpacker.unpackValue().toJson());
                    logger.e("key:" + jsonObj.toString());
                    Log.d( "qwh",jsonObj.toString());
                    if (jsonObj.get("mode").equals("D")) {
                        CtrlInputAction cia = new CtrlInputAction(service);
                        cia.keyCode = jsonObj.getInt("code");
                        cia.shiftKey = jsonObj.getBoolean("shift");
                        cia.altKey = jsonObj.getBoolean("alt");
                        cia.ctrlKey = jsonObj.getBoolean("ctrl");
                        ActionRunner actionRunner = new ActionRunner();
                        actionRunner.setAction(cia);
                        service.handler.post(actionRunner);
                        actionRunner.waitResult();
                    }
                } catch (IOException e) {
                    logger.e("uri key", e);
                } catch (JSONException e) {
                    logger.e("uri key", e);
                }
            }
            return newFixedLengthResponse(null);
        } else if (uri.equals("/text")) {
            CharSequence txt = service.getCurrentInputConnection()
                    .getExtractedText(new ExtractedTextRequest(), 0).text;
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            try {
                packer.packString(txt.toString());
                packer.close();
            } catch (IOException e) {
                logger.e("uri text", e);
            }
            InputStream is = new ByteArrayInputStream(packer.toByteArray());
            return newChunkedResponse(Response.Status.OK, "application/x-msgpack", is);
        } else if (uri.equals("/fill")) {
            if (session.getMethod() == Method.POST) {
                TextInputAction tia = new TextInputAction(service);
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(session.getInputStream());
                try {
                    tia.text = unpacker.unpackString();
                } catch (IOException e) {
                    logger.e("uri fill", e);
                }
                tia.replace_text = true;
                if (tia.text != null) {
                    ActionRunner actionRunner = new ActionRunner();
                    actionRunner.setAction(tia);
                    service.handler.post(actionRunner);
                    actionRunner.waitResult();
                }
            }
            return newFixedLengthResponse(null);
        } else if (uri.equals("/append")) {
            if (session.getMethod() == Method.POST) {
                TextInputAction tia = new TextInputAction(service);
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(session.getInputStream());

                try {

                    tia.text = unpacker.unpackString();
                } catch (IOException e) {
                    logger.e("uri append", e);
                }

                    logger.e(tia.text );

                tia.replace_text = false;
                if (tia.text != null) {
                    ActionRunner actionRunner = new ActionRunner();
                    actionRunner.setAction(tia);
                    service.handler.post(actionRunner);
                    actionRunner.waitResult();
                }
            }
            return newFixedLengthResponse(null);
        }

        // Default return index.html
        return newFixedLengthResponse(loadLocalFile(R.raw.index));
    }

    private String loadLocalFile(@RawRes int id) {
        String data = null;
        InputStream is = null;
        try {
            is =  service.getResources().openRawResource(id);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            data = new String(buffer);
        } catch (IOException e) {
            logger.e("loadLocalFile " + id, e);
        } finally {
            try {
                is.close();
            } catch (IOException ex) {
                logger.e("loadLocalFile close " + id, ex);
            }
        }
        return data;
    }
    private InputStream loadLocalFile(int id,String stype) {
        String data = null;
        InputStream is = null;



            is =  service.getResources().openRawResource(id);


//       Log.d("qwh",data);
        return is;
    }

    /**
     * Wrapper for InputAction. We cannot post InputActions directly to the
     * messagequeue because we use commitText() and sendKeyEvent(). The later
     * executes asynchronously and hence fast commits (e.g. via copy&paste) result
     * in linebreaks being out of order.
     *
     * @author patrick
     */
    public class ActionRunner implements Runnable {

        private Runnable action;
        private boolean finished;

        public void setAction(Runnable action) {
            this.action = action;
            this.finished = false;
        }

        public void run() {
            action.run();
            synchronized (this) {
                finished = true;
                notify();
            }
        }

        public synchronized void waitResult() {
            while (!finished) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }
}

