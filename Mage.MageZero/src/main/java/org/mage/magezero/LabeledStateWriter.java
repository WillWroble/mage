package org.mage.magezero;

import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.HDF5FloatStorageFeatures;
import ch.systemsx.cisd.hdf5.HDF5IntStorageFeatures;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import mage.player.ai.LabeledState;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Row-major HDF5 writer for MageZero datasets.
 *
 * Layout:
 *   /indices : int32 [NNZ]             (CSR values)
 *   /offsets : int64 [N+1]             (CSR row offsets; offsets[0]==0)
 *   /row     : float32 [N, A+4]        (row-major: action(A), resultLabel, stateScore, isPlayer, actionType)
 *
 * Notes:
 * - No compression (fastest random read).
 * - Chunk rows by rowsChunk; columns are fixed width (A+4).
 * - Indices are sorted per row to keep CSR tidy (optional).
 */
public final class LabeledStateWriter implements Closeable, Flushable {

    public final Set<Integer> batchFeatures = new HashSet<>();
    public int batchStates = 0;

    private final int actionDim;   // A

    private final IHDF5Writer writer;

    private long nRows = 0; // N
    private long nNnz  = 0; // total entries in /indices

    public LabeledStateWriter(String path) throws IOException {
        this(path, /*actionDim*/128, /*rowsChunk*/2048, /*idxChunk*/1_000_000);
    }

    public LabeledStateWriter(String path, int actionDim, int rowsChunk, int idxChunk) throws IOException {
        this.actionDim = actionDim;
        // chunk size for rows
        // chunk size for /indices

        try {
            this.writer = HDF5Factory.configure(path)
                    .overwrite()
                    .useUTF8CharacterEncoding()
                    .writer();

            // indices: 1D int32, extendable, uncompressed
            writer.int32().createArray(
                    "/indices",
                    /*initialSize*/0L,
                    /*blockSize*/idxChunk,
                    HDF5IntStorageFeatures.INT_NO_COMPRESSION
            );

            // offsets: 1D int64, extendable, uncompressed; seed with one 0
            writer.int64().createArray(
                    "/offsets",
                    /*initialSize*/1L,
                    /*blockSize*/Math.max(rowsChunk, 512),
                    HDF5IntStorageFeatures.INT_NO_COMPRESSION
            );
            writer.int64().writeArrayBlockWithOffset("/offsets", new long[]{0L}, 1, 0L);

            // row: 2D float32 [N, actionDim+4], extendable rows, row-major chunks, uncompressed
            int rowWidth = actionDim + 4;
            writer.float32().createMatrix(
                    "/row",
                    /*sizeX rows*/0L,
                    /*sizeY cols*/rowWidth,
                    /*blockX*/rowsChunk,
                    /*blockY*/rowWidth,
                    HDF5FloatStorageFeatures.FLOAT_NO_COMPRESSION
            );
        } catch (Exception e) {
            throw new IOException("Failed to initialize HDF5 writer", e);
        }
    }

    /** Append one record. */
    public synchronized void writeRecord(LabeledState s) throws IOException {
        try {
            // --- CSR append for stateVector ---
            int[] sv = s.stateVector.stream().mapToInt(Integer::intValue).toArray();
            if (sv.length > 1) Arrays.sort(sv);
            if (sv.length > 0) {
                writer.int32().writeArrayBlockWithOffset("/indices", sv, sv.length, nNnz);
                nNnz += sv.length;
            }
            writer.int64().writeArrayBlockWithOffset("/offsets", new long[]{ nNnz }, 1, nRows + 1);

            // --- pack fixed row as float32 ---
            final int W = actionDim + 4;
            float[] row = new float[W];

            // actions (truncate/pad)
            int copy = Math.min(actionDim, s.actionVector.length);
            for (int i = 0; i < copy; i++) row[i] = (float) s.actionVector[i];
            for (int i = copy; i < actionDim; i++) row[i] = 0f;

            // scalars
            row[actionDim] = (float) s.resultLabel;         // resultLabel
            row[actionDim + 1] = (float) s.stateScore;          // stateScore
            row[actionDim + 2] = s.isPlayer ? 1f : 0f;          // isPlayer
            row[actionDim + 3] = (float) s.actionType.ordinal();// actionType as float

            // write one row
            writer.float32().writeMatrixBlockWithOffset(
                    "/row",
                    new float[][]{ row },
                    /*blockSizeX*/1,
                    /*blockSizeY*/W,
                    /*offsetX*/nRows,
                    /*offsetY*/0
            );

            nRows++;
            batchStates++;
            batchFeatures.addAll(s.stateVector);
        } catch (Exception e) {
            throw new IOException("HDF5 append failed", e);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        try { writer.file().flush(); }
        catch (Exception e) { throw new IOException("HDF5 flush failed", e); }
    }

    @Override
    public synchronized void close() throws IOException {
        try { flush(); } catch (IOException ignored) {}
        try { writer.close(); } catch (Exception ignored) {}
    }
}
