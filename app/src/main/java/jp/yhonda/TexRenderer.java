package jp.yhonda;

import android.content.Context;
import android.content.SharedPreferences;
import android.helpers.CallbackWithFailure;
import android.os.Build;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import junit.framework.Assert;

import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TexRenderer {

    public static final String TAG = "MoA";

    private WebView webview;

    private final Semaphore formulaRenderSem = new Semaphore(1);
    private CallbackWithFailure<String, String> resultConsumer;

    public TexRenderer(final Context cxt, final SharedPreferences settings) {
        webview = new WebView(cxt);

        webview.getSettings().setJavaScriptEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        final float sc = settings.getFloat("maxima main scale", 1.5f);
        webview.setInitialScale((int) (100 * sc));

        webview.getSettings().setBuiltInZoomControls(true);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
            webview.getSettings().setDisplayZoomControls(false);
        }

        webview.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(final ConsoleMessage msg) {
                Log.d(TAG, "JS: " + msg.message());
                return true;
            }
        });

        webview.loadUrl("file:///android_asset/math-renderer.html");

        /*
        {
            final StringBuilder buf = new StringBuilder();
            final InputStream is = getAssets().open("math-renderer.html");
            final BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String str;

            while ((str = in.readLine()) != null) {
                buf.append(str);
            }

            in.close();

            webview.loadData(buf.toString(), "text/html", null);
        }
        */

    }

    public void renderTex(final String tex, final CallbackWithFailure<String, String> k) {
        resultConsumer = k;
        try {
            Log.d(TAG, "renderTex");
            formulaRenderSem.acquire();
        } catch (InterruptedException e) {
            Log.d(TAG, "failed to acquire semaphore: " + e);
            resultConsumer.onFailure("failed to acquire semaphore: " + e);
        }

        final String formula = prepareFormulaForMathJax(tex);
        Log.d(TAG, "Sending formula to mathjax: " + formula);
        webview.loadUrl("javascript:window.RenderTex('" + formula + "')");
    }

    @JavascriptInterface
    public void onFormulaRendered(final String svg) {
        Log.d(TAG, "Got tex from mathjax: " + svg);
        formulaRenderSem.release();
        resultConsumer.acceptResult(svg);
    }

    private static String prepareFormulaForMathJax(final String formula) {
        return substituteMBOXVERB(
                substCRinMBOX(formula).replace("\n", " \\\\\\\\ "));
    }

    private static String substCRinMBOX(final String str) {
        final StringBuilder res = new StringBuilder();
        String tmp = str;
        int startPos = -1;
        while ((startPos = tmp.indexOf("mbox{")) != -1) {
            res.append(tmp.substring(0, startPos));
            res.append("mbox{");
            final int contentStart = startPos + 5;
            final int contentEnd = tmp.indexOf("}", contentStart);
            Assert.assertTrue(contentEnd > 0);
            final String contents = tmp.substring(contentStart, contentEnd);
            res.append(contents.replace("\n", "}\\\\\\\\ \\\\mbox{"));
            tmp = tmp.substring(contentEnd, tmp.length());
        }
        res.append(tmp);
        return new String(res);
    }

    static private String substituteMBOXVERB(String texStr) {
        final Pattern pat = Pattern.compile("\\\\\\\\mbox\\{\\\\\\\\verb\\|(.)\\|\\}");
        final Matcher m = pat.matcher(texStr);
        final StringBuffer sb = new StringBuffer();
        if (m.find()) {
            m.appendReplacement(sb,"\\\\\\\\text{$1");
            while (m.find()) {
                m.appendReplacement(sb, "$1");
            }
            m.appendTail(sb);
            return sb.toString() + "}";
        }
        return texStr;
    }

}
