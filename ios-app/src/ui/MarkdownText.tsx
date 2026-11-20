import { Linking, StyleSheet, Text, View } from 'react-native';
import { parseMarkdown, type Inline } from './markdown';
import type { Theme } from './theme';

function renderInline(nodes: Inline[], theme: Theme, keyPrefix = ''): React.ReactNode[] {
  return nodes.map((n, i) => {
    const key = `${keyPrefix}${i}`;
    switch (n.type) {
      case 'text':
        return n.text;
      case 'bold':
        return (
          <Text key={key} style={styles.bold}>
            {renderInline(n.children, theme, `${key}.`)}
          </Text>
        );
      case 'italic':
        return (
          <Text key={key} style={styles.italic}>
            {renderInline(n.children, theme, `${key}.`)}
          </Text>
        );
      case 'code':
        return (
          <Text key={key} style={[styles.code, { backgroundColor: theme.code }]}>
            {n.text}
          </Text>
        );
      case 'link':
        return (
          <Text key={key} style={{ color: theme.primary, textDecorationLine: 'underline' }} onPress={() => void Linking.openURL(n.url)}>
            {n.text}
          </Text>
        );
    }
  });
}

/** Edith's reply as Markdown: blocks laid out as separate views, so list items don't pick up paragraph spacing. */
export function MarkdownText({ text, color, theme }: { text: string; color: string; theme: Theme }) {
  const blocks = parseMarkdown(text);
  return (
    <View style={{ gap: 6 }}>
      {blocks.map((b, i) => {
        switch (b.type) {
          case 'code':
            return (
              <View key={i} style={[styles.codeBlock, { backgroundColor: theme.code }]}>
                <Text style={[styles.codeText, { color }]}>{b.text}</Text>
              </View>
            );
          case 'heading':
            return (
              <Text key={i} style={[styles.text, styles.bold, { color, fontSize: b.level <= 2 ? 19 : 17 }]}>
                {renderInline(b.inlines, theme)}
              </Text>
            );
          case 'bullet':
          case 'numbered':
            return (
              <View key={i} style={styles.listRow}>
                <Text style={[styles.text, styles.marker, { color }]}>{b.type === 'bullet' ? '•' : `${b.number}.`}</Text>
                <Text style={[styles.text, styles.flex, { color }]}>{renderInline(b.inlines, theme)}</Text>
              </View>
            );
          case 'quote':
            return (
              <View key={i} style={[styles.quote, { borderLeftColor: theme.primary }]}>
                <Text style={[styles.text, { color }]}>{renderInline(b.inlines, theme)}</Text>
              </View>
            );
          case 'paragraph':
            return (
              <Text key={i} style={[styles.text, { color }]}>
                {renderInline(b.inlines, theme)}
              </Text>
            );
        }
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  text: { fontSize: 16, lineHeight: 22 },
  bold: { fontWeight: '700' },
  italic: { fontStyle: 'italic' },
  code: { fontFamily: 'Menlo', fontSize: 14 },
  codeBlock: { borderRadius: 8, padding: 8 },
  codeText: { fontFamily: 'Menlo', fontSize: 13 },
  listRow: { flexDirection: 'row', gap: 6 },
  marker: { minWidth: 18 },
  flex: { flex: 1 },
  quote: { borderLeftWidth: 3, paddingLeft: 8 },
});
