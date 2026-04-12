package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.stream.IStream;

public interface ILiveStream extends IStream {
    StreamAgenda getAgenda();
    void setAgenda(StreamAgenda agenda);

}
