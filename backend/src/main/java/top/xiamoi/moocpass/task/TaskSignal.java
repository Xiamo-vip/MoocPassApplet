package top.xiamoi.moocpass.task;

public class TaskSignal extends RuntimeException {
    private final String state;
    private final String code;
    public TaskSignal(String state, String code, String message) {
        super(message);
        this.state = state; this.code = code;
    }
    public String state() { return state; }
    public String code() { return code; }
}
