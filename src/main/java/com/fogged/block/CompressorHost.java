package com.fogged.block;

/**
 * A block entity that houses an {@link AirCompressorCore}: the FE {@link AirCompressorBlockEntity}, or
 * with Create the kinetic compressor's. Units, rendering and the room all reach the core through this,
 * whichever kind of compressor it is.
 */
public interface CompressorHost {

    AirCompressorCore core();

    /** Send the client what {@link AirCompressorCore#writeClient} writes, without re-sending the block. */
    void syncToClient();
}
