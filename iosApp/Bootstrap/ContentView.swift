import SwiftUI
import QRRepairCore

struct ContentView: View {
    private let core = QRRepairCoreApi()

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 64))
                Text("QRevy")
                    .font(.title)
                Text("Shared core v\(core.version())")
                    .font(.headline)
                Label(
                    core.reedSolomonSelfTest() ? "Shared RS self-test passed" : "Shared RS self-test failed",
                    systemImage: core.reedSolomonSelfTest() ? "checkmark.seal.fill" : "xmark.octagon.fill"
                )
                Text("The shared recovery core is ready for SwiftUI integration. Photo import is the next milestone.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle("QRevy")
        }
    }
}
