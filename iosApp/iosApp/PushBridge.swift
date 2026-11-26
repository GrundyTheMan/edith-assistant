import UIKit
import UserNotifications
import EdithUI

/// Apple's push for camera alerts while Edith is closed (K6.2): the permission prompt, the device token and the tap. Everything else is Kotlin (ApplePush, PushRegistration).
/// While Edith is open she shows her own banner, so a push that arrives in front is not shown (no double alert); a tap on one from the lock screen opens the clips.
final class PushBridge: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, SwiftPushRequester {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        ApplePush.shared.requester = self
        return true
    }

    /// Kotlin asks (when the owner switches "also when closed" on, and at each launch while it is on).
    func requestPermissionAndRegister() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async {
                if granted { UIApplication.shared.registerForRemoteNotifications() } else { ApplePush.shared.onDenied() }
            }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        ApplePush.shared.onToken(hex: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        ApplePush.shared.onDenied()
    }

    /// In front: Edith shows her own alert, so nothing from Apple.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        ApplePush.shared.onTapped()
        completionHandler()
    }
}
