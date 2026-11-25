package btools.router;

import java.util.List;

/**
 * Lee las indicaciones de giro que arma BRouter (VoiceHint), cuyos campos son internos del
 * paquete: por eso esta clase vive en btools.router. Devuelve, por cada giro,
 * {índice del punto en la ruta, comando (VoiceHint.C, TL, TR…), número de salida de rotonda}.
 */
public final class CalandriaHints {
    private CalandriaHints() {}

    public static int[][] of(OsmTrack track) {
        VoiceHintList hints = track.voiceHints;
        if (hints == null || hints.list == null) return new int[0][];
        List<VoiceHint> list = hints.list;
        int[][] out = new int[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            VoiceHint h = list.get(i);
            if (h.cmd == 0) h.calcCommand();
            out[i] = new int[] {h.indexInTrack, h.cmd, h.getExitNumber()};
        }
        return out;
    }
}
