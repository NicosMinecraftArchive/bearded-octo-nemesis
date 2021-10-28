package immibis.bon;

public interface IProgressListener {
    void start(int max, String text);

    void set(int value);

    void setMax(int max);
}
