/**
 * Standing checks from the parity audit (C76 §3): rules about the whole app that a person would otherwise have to remember. They read the source with the
 * TypeScript compiler, so a new screen that breaks one fails here instead of on the owner's phone.
 */
import * as fs from 'fs';
import * as path from 'path';
import * as ts from 'typescript';

const SRC = path.join(__dirname);
function sourceFiles(ext: RegExp, skipTests = true): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (ext.test(e.name) && !(skipTests && /\.test\./.test(e.name))) out.push(p);
    }
  };
  walk(SRC);
  return out;
}
const parse = (file: string) => ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
const rel = (file: string) => path.relative(SRC, file).replace(/\\/g, '/');

describe('VoiceOver (C76 §3)', () => {
  /** Controls a person taps or edits. `Switch` and `Slider` carry their role natively; `Image` is checked on its own. The local `Button` component is a Pressable with text. */
  const NEEDS_NAME = new Set(['Pressable', 'TouchableOpacity', 'TextInput', 'Switch', 'Slider']);

  it('every tappable control has a name VoiceOver can read, and every Pressable a role', () => {
    const problems: string[] = [];
    for (const file of sourceFiles(/\.tsx$/)) {
      const sf = parse(file);
      const visit = (node: ts.Node) => {
        const open = ts.isJsxSelfClosingElement(node) ? node : ts.isJsxOpeningElement(node) ? node : null;
        if (open) {
          const name = open.tagName.getText(sf);
          if (NEEDS_NAME.has(name)) {
            const attrs = new Set(open.attributes.properties.filter(ts.isJsxAttribute).map((a) => a.name.getText(sf)));
            const spread = open.attributes.properties.some(ts.isJsxSpreadAttribute);
            const labelled = attrs.has('accessibilityLabel') || attrs.has('aria-label') || spread;
            // A Pressable that contains text is read by that text; an input with a placeholder by the placeholder.
            const hasText = ts.isJsxOpeningElement(open) && open.parent.children.some((c) => (ts.isJsxElement(c) && /^<Text[\s>]/.test(c.getText(sf))) || (ts.isJsxText(c) && c.getText(sf).trim() !== ''));
            const named = labelled || hasText || (name === 'TextInput' && attrs.has('placeholder'));
            const { line } = sf.getLineAndCharacterOfPosition(open.getStart(sf));
            if (!named) problems.push(`${rel(file)}:${line + 1} <${name}> has no accessibilityLabel`);
            if ((name === 'Pressable' || name === 'TouchableOpacity') && !attrs.has('accessibilityRole') && !attrs.has('role') && !spread) problems.push(`${rel(file)}:${line + 1} <${name}> has no accessibilityRole`);
          }
        }
        ts.forEachChild(node, visit);
      };
      visit(sf);
    }
    expect(problems).toEqual([]);
  });

  it('never turns off font scaling (Dynamic Type)', () => {
    const offenders = sourceFiles(/\.tsx?$/).filter((f) => /allowFontScaling=\{false\}|allowFontScaling:\s*false/.test(fs.readFileSync(f, 'utf8')));
    expect(offenders.map(rel)).toEqual([]);
  });
});

describe('privacy (C76 §3)', () => {
  it('log.info and log.warn never print an error\'s message, a stringified object or a payload: only its name, a status or our own words', () => {
    const problems: string[] = [];
    for (const file of sourceFiles(/\.tsx?$/)) {
      const sf = parse(file);
      const visit = (node: ts.Node) => {
        if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && node.expression.expression.getText(sf) === 'log' && ['info', 'warn'].includes(node.expression.name.text)) {
          const text = node.arguments.map((a) => a.getText(sf)).join(' ');
          if (/\.message\b|\bString\(|JSON\.stringify|\.body\b|\.text\(\)/.test(text)) {
            const { line } = sf.getLineAndCharacterOfPosition(node.getStart(sf));
            problems.push(`${rel(file)}:${line + 1} ${text.slice(0, 90)}`);
          }
        }
        ts.forEachChild(node, visit);
      };
      visit(sf);
    }
    expect(problems).toEqual([]);
  });

  it('conversation text is logged only through log.sensitive (development builds)', () => {
    const files = sourceFiles(/\.tsx?$/).filter((f) => /log\.sensitive\(/.test(fs.readFileSync(f, 'utf8')));
    expect(files.map(rel)).toEqual(['tools/framework/toolRunner.ts']);
  });

  it('a card that only a tap may answer is answered only from the screen (C39 §10: cancel a print is never a voice)', () => {
    // `screenOnly` cards (cancelling a print) must never be approved by a spoken yes. Today the chat screen's button is the only caller: a voice path (C57, C69) that wants to
    // answer a card must check `screenOnly` first and leave it to the screen, and then this list changes on purpose.
    const callers = sourceFiles(/\.tsx?$/).filter((f) => /\.resolveConfirmation\(/.test(fs.readFileSync(f, 'utf8')));
    expect(callers.map(rel)).toEqual(['ui/ChatScreen.tsx']);
  });

  it('nothing but core/log.ts uses console', () => {
    const offenders = sourceFiles(/\.tsx?$/).filter((f) => rel(f) !== 'core/log.ts' && /\bconsole\./.test(fs.readFileSync(f, 'utf8')));
    expect(offenders.map(rel)).toEqual([]);
  });

  it('secrets go to the Keychain: the key-value store is never given a token, key or password', () => {
    const problems: string[] = [];
    for (const file of sourceFiles(/\.tsx?$/)) {
      const sf = parse(file);
      const visit = (node: ts.Node) => {
        if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'setItem') {
          const text = node.arguments.map((a) => a.getText(sf)).join(' ');
          if (/token|secret|password|passwd|apikey|api_key|cookie|csrf/i.test(text)) {
            const { line } = sf.getLineAndCharacterOfPosition(node.getStart(sf));
            problems.push(`${rel(file)}:${line + 1} ${text.slice(0, 90)}`);
          }
        }
        ts.forEachChild(node, visit);
      };
      visit(sf);
    }
    expect(problems).toEqual([]);
  });

  it('the database holds no attachment bytes (files live on disk; the table keeps sizes and names)', () => {
    const sql = fs.readFileSync(path.join(SRC, 'data', 'migrations.ts'), 'utf8');
    expect(sql).not.toMatch(/\bBLOB\b/i);
    expect(sql).not.toMatch(/base64/i);
  });
});
