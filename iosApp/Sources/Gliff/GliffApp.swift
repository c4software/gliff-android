import Shared
import SwiftUI

/// What lives as long as the process: the Kotlin side's graph, made with the
/// ssh client it connects through.
enum Gliff {
    static let graph = IosGraph(bridge: SshConnector())
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// A phone holds a session upright until its button lays it down: the
    /// shared code decides, and UIKit asks here.
    func application(
        _ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        UIInterfaceOrientationMask(rawValue: UInt(Gliff.graph.orientations))
    }
}

@main
struct GliffApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        // The list of machines. On a phone, a session opens in this window too.
        WindowGroup {
            Window(machine: nil)
        }
        // On an iPad each machine has a window of its own, so several can sit
        // side by side; opening a machine again brings its window back.
        WindowGroup(for: String.self) { $machine in
            if let machine {
                Window(machine: machine)
            }
        }
    }
}

/// One window: the shared screens, edge to edge. They deal with the safe
/// area and the keyboard themselves.
private struct Window: View {
    let machine: String?

    @Environment(\.supportsMultipleWindows) private var multipleWindows
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Screens(
            machine: machine,
            openElsewhere: { id in
                guard multipleWindows else { return false }
                openWindow(value: id)
                return true
            },
            leave: { dismissWindow() }
        )
        .ignoresSafeArea()
        .onDisappear {
            // A machine's window closed by the system's own gesture: its session goes with it.
            if let machine { Gliff.graph.sessions.endSession(machineId: machine) }
        }
    }
}

private struct Screens: UIViewControllerRepresentable {
    let machine: String?
    let openElsewhere: (String) -> Bool
    let leave: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController(
            graph: Gliff.graph,
            only: machine,
            openElsewhere: { id in KotlinBoolean(bool: openElsewhere(id)) },
            onLeave: leave
        )
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
