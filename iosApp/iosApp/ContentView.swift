import SwiftUI
import UIKit
import EdithUI

/// Shows the shared Compose screens. This file and iOSApp.swift are the whole Swift side of the app so far.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(rtc: RingRtcFactory())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea()
    }
}
