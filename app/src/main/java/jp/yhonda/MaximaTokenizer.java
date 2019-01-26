package jp.yhonda;

import android.widget.MultiAutoCompleteTextView;

/**
 * Created by yasube on 2017/03/08.
 */

public class MaximaTokenizer implements MultiAutoCompleteTextView.Tokenizer {

    private final static String delimiter = "()[],.;:+*/-=<>`!#$%&'^";

    @Override
    public int findTokenStart(final CharSequence text, final int cursor) {
        int i = cursor;
        while (i > 0 && delimiter.indexOf(text.charAt(i - 1)) == -1) {
            i--;
        }
        while (i < cursor && text.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    @Override
    public int findTokenEnd(final CharSequence text, final int cursor) {
        int i   = cursor;
        int len = text.length();
        while (i < len) {
            if (delimiter.indexOf(text.charAt(i)) > 0) {
                return i;
            } else {
                i++;
            }
        }
        return len;
    }

    @Override
    public CharSequence terminateToken(CharSequence text) {
        return text;
    }

}
