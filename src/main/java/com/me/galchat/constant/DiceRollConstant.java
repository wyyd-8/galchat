package com.me.galchat.constant;

public final class DiceRollConstant {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String TYPE_CHECK = "CHECK";
    public static final String TYPE_OPPOSED_CHECK = "OPPOSED_CHECK";
    public static final String TYPE_SAN_CHECK = "SAN_CHECK";
    public static final String TYPE_SAN_LOSS = "SAN_LOSS";
    public static final String TYPE_TEMPORARY_INSANITY_TYPE = "TEMPORARY_INSANITY_TYPE";
    public static final String TYPE_TEMPORARY_INSANITY_DURATION = "TEMPORARY_INSANITY_DURATION";
    public static final String TYPE_DAMAGE = "DAMAGE";
    public static final String TYPE_MAJOR_WOUND_CON = "MAJOR_WOUND_CON";

    public static final String TOOL_REQUEST_CHECK = "requestCheck";
    public static final String TOOL_REQUEST_SAN_CHECK = "requestSanCheck";
    public static final String TOOL_REQUEST_PUSHED_CHECK = "requestPushedCheck";
    public static final String TOOL_REQUEST_OPPOSED_CHECK = "requestOpposedCheck";

    private DiceRollConstant() {
    }
}
