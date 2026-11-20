import { useColorScheme } from 'react-native';

export interface Theme {
  dark: boolean;
  background: string;
  surface: string;
  text: string;
  textMuted: string;
  primary: string;
  onPrimary: string;
  edithBubble: string;
  onEdithBubble: string;
  border: string;
  error: string;
  code: string;
}

const light: Theme = {
  dark: false,
  background: '#ffffff',
  surface: '#f4f1f8',
  text: '#1b1b1f',
  textMuted: '#66626d',
  primary: '#6b3fa0',
  onPrimary: '#ffffff',
  edithBubble: '#ebe8ee',
  onEdithBubble: '#1b1b1f',
  border: '#d6d2dc',
  error: '#b3261e',
  code: '#dcd7e2',
};

const dark: Theme = {
  dark: true,
  background: '#111114',
  surface: '#1c1b20',
  text: '#e6e1e9',
  textMuted: '#a9a4b0',
  primary: '#b794e6',
  onPrimary: '#2a1545',
  edithBubble: '#2b2930',
  onEdithBubble: '#e6e1e9',
  border: '#3a3740',
  error: '#f2b8b5',
  code: '#3a3740',
};

export const themes = { light, dark };

/** Light or dark, following the iPhone's setting. */
export function useTheme(): Theme {
  return useColorScheme() === 'dark' ? dark : light;
}
