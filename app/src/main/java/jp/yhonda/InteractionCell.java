package jp.yhonda;

import junit.framework.Assert;

public class InteractionCell {
    public final int identifier;
    public final String input;
    public final String output;
    public final OutputType outputType;
    public final boolean isNotice;
	// Label may be empty meaning "no label".
    public final String outputLabel;

    public enum OutputType {
        OutputText, OutputSvg
    }

    public InteractionCell(final int identifier, final String input, final String output, final OutputType outputType, final boolean isNotice, final String outputLabel) {
        Assert.assertNotNull(input);
        Assert.assertNotNull(output);
        Assert.assertNotNull(outputType);
        Assert.assertNotNull(isNotice);
        Assert.assertNotNull(outputLabel);
        this.identifier  = identifier;
        this.input       = input;
        this.output      = output;
        this.outputType  = outputType;
        this.isNotice    = isNotice;
        this.outputLabel = outputLabel;
    }
}
