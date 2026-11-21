import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { useServices } from '../core/services';
import { PrinterWords } from '../printer/feed/words';
import type { Theme } from './theme';

/** What Jarvis says about the printer, in words (C39 §9.1). */
export const PRINTER_JARVIS_NOTE = 'To see and control the printer away from home, pair Edith with Jarvis (the Jarvis service above) and, in Jarvis, switch on "Let paired phones see the printer".';

/** Under the printer's row: how to use it away from home, and what the paired Jarvis says right now. */
export function PrinterJarvisNote({ theme }: { theme: Theme }) {
  const { printerFeed } = useServices();
  const [line, setLine] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    void printerFeed
      ?.access()
      .then((a) => {
        if (!live) return;
        setLine(a.kind === 'ready' ? `Jarvis: sharing the printer (${a.shared.name})` : a.kind === 'notShared' ? 'Jarvis: not sharing the printer' : a.kind === 'noJarvis' ? 'Jarvis: not paired' : `Jarvis: ${a.removed ? PrinterWords.UNAUTHORIZED : "can't be reached right now"}`);
      })
      .catch(() => live && setLine("Jarvis: can't be reached right now"));
    return () => {
      live = false;
    };
  }, [printerFeed]);
  return (
    <View style={{ gap: 4 }} testID="printer-jarvis-note">
      <Text style={{ color: theme.textMuted, fontSize: 13 }}>{PRINTER_JARVIS_NOTE}</Text>
      {line ? (
        <Text style={{ color: theme.text, fontSize: 13 }} testID="printer-jarvis-status">
          {line}
        </Text>
      ) : null}
    </View>
  );
}
