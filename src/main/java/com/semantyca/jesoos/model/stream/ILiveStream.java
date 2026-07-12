package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.stream.IStream;

import java.util.Map;

public interface ILiveStream extends IStream {
    StreamAgenda getAgenda();
    void setAgenda(StreamAgenda agenda);
    Map<String, Object> getUserVariables();

}
