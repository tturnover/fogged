package com.fogged.block;

/**
 * Marks an air compressor block -- the FE one, or with Create the kinetic one -- so the shared rules
 * (its intake behind it, units) can tell a compressor from any other block.
 * Every compressor has {@link AirCompressorBlock#FACING} for its outlet.
 */
public interface CompressorBlock {
}
