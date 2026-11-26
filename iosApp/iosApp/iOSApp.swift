import SwiftUI

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(PushBridge.self) private var push

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
