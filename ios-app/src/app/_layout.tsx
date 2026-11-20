import { Stack } from 'expo-router';
import { ActivityIndicator, Text, View } from 'react-native';
import { ServicesProvider } from '../core/services';

function Loading({ error }: { error: string | null }) {
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12 }}>
      {error ? <Text selectable>{`Edith couldn't start: ${error}`}</Text> : <ActivityIndicator />}
    </View>
  );
}

export default function RootLayout() {
  return (
      <ServicesProvider fallback={(error) => <Loading error={error} />}>
        <Stack screenOptions={{ headerShown: false }}>
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="conversations" options={{ presentation: 'modal' }} />
          <Stack.Screen name="hello" options={{ presentation: 'modal' }} />
        </Stack>
      </ServicesProvider>
  );
}
