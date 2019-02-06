package jp.yhonda;

public class InteractionCell {
    public final String input;
    public final String output;
    public final OutputType outputType;
    public final boolean isNotice;
    public final String outputLabel;

    public enum OutputType {
        OutputText, OutputSvg
    }

    public InteractionCell(final String input, final String output, final OutputType outputType, final boolean isNotice, final String outputLabel) {
        this.input       = input;
        this.output      = output;
        this.outputType  = outputType;
        this.isNotice    = isNotice;
        this.outputLabel = outputLabel;
    }
}
