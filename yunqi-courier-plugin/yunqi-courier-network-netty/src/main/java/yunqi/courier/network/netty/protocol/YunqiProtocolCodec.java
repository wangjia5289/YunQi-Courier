package yunqi.courier.network.netty.protocol;

import yunqi.courier.common.constant.CourierConstants;

public final class YunqiProtocolCodec {

    public static final int HEADER_LENGTH = 19;

    public static final int MAX_FRAME_LENGTH = CourierConstants.MAX_MAX_FRAME_LENGTH;

    private YunqiProtocolCodec() {
    }
}
