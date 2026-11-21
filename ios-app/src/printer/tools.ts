import { printerWords } from '../home/actions';
import type { PrintJob } from '../home/model';
import type { Home } from '../home/home';
import { failed, ok, type Tool } from '../tools/framework/tool';

/** Through Jarvis it says so; and the printer's own latest error line is mentioned even while it is still printing (C39 §9.3). */
export function extras(job: PrintJob | undefined): string {
  if (!job) return '';
  const alert = job.message && job.message.trim() && job.state !== 'error' && job.state !== 'cancelled' ? job.message : null;
  return `${job.viaJarvis ? ' (via Jarvis)' : ''}${alert ? `. The printer's latest error line: ${alert}` : ''}`;
}

/** `printer_status` (C55): "How's the print going?". Read-only. Pause, resume and cancel go through `home_control` (and its cards). */
export function printerStatusTool(home: Home): Tool {
  return {
    spec: {
      name: 'printer_status',
      description: "Says how the 3D printer is doing: what it is printing, progress, time left, nozzle and bed temperatures. Use it for \"how's the print going?\".",
      parameters: [],
    },
    statusLabel: 'Checking the printer…',
    displayName: 'Printer',
    enabled: () => home.visible().some((d) => d.type === 'printer'),
    async execute() {
      const snapshot = await home.refresh();
      const printer = snapshot.devices.find((d) => d.type === 'printer' && !home.isHidden(d.id));
      if (!printer) {
        const why = snapshot.problems.find((p) => p.startsWith('3D printer'));
        return failed(why ? `Couldn't read the printer. ${why}` : 'No 3D printer is connected. The owner can add it under Settings → Home.');
      }
      return ok(`${printer.name}: ${printerWords(printer)}${extras(printer.state.print)}`);
    },
  };
}
