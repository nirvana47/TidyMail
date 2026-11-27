import AppKit
import Darwin
import Security

@main
enum UninstallHelper {
    static func main() {
        umask(0o077)
        if CommandLine.arguments == [CommandLine.arguments[0], "--self-test"] {
            do {
                try selfTest()
                exit(0)
            } catch {
                fputs("Uninstall check failed: \(error.localizedDescription)\n", stderr)
                exit(1)
            }
        }
        guard CommandLine.arguments.count == 3,
            ["--plan", "--test-plan"].contains(CommandLine.arguments[1])
        else { exit(2) }
        let testing = CommandLine.arguments[1] == "--test-plan"
        let ownFolder = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
            .deletingLastPathComponent()
        let planPath = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
        let temp = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
        guard ownFolder.deletingLastPathComponent().resolvingSymlinksInPath() == temp,
            ownFolder.lastPathComponent.hasPrefix("tidymail-uninstaller-"),
            planPath == ownFolder.appendingPathComponent("plan.json")
        else { exit(2) }
        let result: UninstallResult
        do {
            let plan = try JSONDecoder().decode(
                UninstallPlan.self, from: Data(contentsOf: planPath))
            guard (plan.testRoot != nil) == testing else {
                throw UninstallError("The uninstall mode does not match its installation.")
            }
            _ = try plan.validate()
            FileHandle.standardOutput.write(Data("READY\n".utf8))
            try Uninstaller.waitForParent(plan.parentPID)
            result = try Uninstaller.remove(plan)
        } catch {
            result = UninstallResult(message: error.localizedDescription, complete: false)
        }
        // This executable is already mapped into memory. Remove its temporary
        // copy and plan before presenting the result so closing the dialog leaves
        // no persistent helper, background service or credentials behind.
        let cleanupSucceeded: Bool
        // AppKit's result dialog must not start with a deleted working directory.
        FileManager.default.changeCurrentDirectoryPath(NSHomeDirectory())
        do {
            try FileManager.default.removeItem(at: ownFolder)
            cleanupSucceeded = true
        } catch { cleanupSucceeded = false }
        if testing {
            print(result.message)
            exit(result.complete && cleanupSucceeded ? 0 : 1)
        }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        app.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText =
            result.complete && cleanupSucceeded
            ? "TidyMail was uninstalled" : "Uninstall needs attention"
        alert.informativeText =
            result.message
            + (cleanupSucceeded
                ? ""
                : "\n\nThe temporary uninstall helper could not delete itself. Remove this folder: \(ownFolder.path)")
        alert.addButton(withTitle: "OK")
        alert.runModal()
        exit(result.complete && cleanupSucceeded ? 0 : 1)
    }

    static func selfTest() throws {
        let fm = FileManager.default
        func require(_ condition: Bool, _ message: String) throws {
            if !condition { throw UninstallError(message) }
        }
        func fixture() throws -> (URL, URL, URL, UninstallPlan) {
            let root = fm.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(
                "tidymail-uninstall-check-\(UUID().uuidString)")
            let home = root.appendingPathComponent("Data")
            let app = root.appendingPathComponent("TidyMail.app")
            try fm.createDirectory(
                at: home.appendingPathComponent("Backups"), withIntermediateDirectories: true)
            try fm.createDirectory(
                at: home.appendingPathComponent("Logs"), withIntermediateDirectories: true)
            try fm.createDirectory(
                at: app.appendingPathComponent("Contents"), withIntermediateDirectories: true)
            try Data("synthetic metadata".utf8).write(to: home.appendingPathComponent("database"))
            try Data("synthetic backup".utf8).write(
                to: home.appendingPathComponent("Backups/copy.dump"))
            try Data("synthetic log".utf8).write(
                to: home.appendingPathComponent("Logs/application.log"))
            let plist = ["CFBundleIdentifier": "work.tidymail.desktop"]
            try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
                .write(to: app.appendingPathComponent("Contents/Info.plist"))
            return (
                root, home, app,
                try UninstallPlan.create(
                    home: home, application: app, parentPID: getpid(), testRoot: root)
            )
        }
        // Actual Keychain add/read/delete, scoped to a unique temporary installation.
        let (root, home, application, plan) = try fixture()
        defer {
            try? fm.removeItem(at: root)
            try? InstallationCredentials.delete(for: home)
        }
        var item = InstallationCredentials.query(for: home)
        item[kSecValueData as String] = Data("synthetic-installation-credentials".utf8)
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
            throw UninstallError("Could not create the isolated test Keychain entry.")
        }
        let unrelated = root.appendingPathComponent("unrelated.txt")
        try Data("keep".utf8).write(to: unrelated)
        for path in Uninstaller.auxiliaryPaths(plan) {
            try fm.createDirectory(
                at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("synthetic setting".utf8).write(to: path)
        }
        let result = try Uninstaller.remove(plan)
        try require(
            Uninstaller.auxiliaryPaths(plan).allSatisfy { !fm.fileExists(atPath: $0.path) },
            "Settings or cache remained.")
        try require(
            result.complete && !fm.fileExists(atPath: home.path)
                && !fm.fileExists(atPath: application.path), "Data or app remained after uninstall."
        )
        try require(
            SecItemCopyMatching(InstallationCredentials.query(for: home) as CFDictionary, nil)
                == errSecItemNotFound, "Keychain entry remained.")
        try require(fm.fileExists(atPath: unrelated.path), "Unrelated file was removed.")

        let (busyRoot, busyHome, busyApp, busyPlan) = try fixture()
        defer { try? fm.removeItem(at: busyRoot) }
        let fd = Darwin.open(
            busyHome.appendingPathComponent("launcher.lock").path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0, flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            throw UninstallError("Could not prepare lock test.")
        }
        defer { close(fd) }
        var touchedKeys = false
        do {
            _ = try Uninstaller.remove(busyPlan, deleteCredentials: { _ in touchedKeys = true })
            throw UninstallError("Active installation was removed.")
        } catch let error as UninstallError {
            try require(
                error.message.contains("Another copy"), "Unexpected locked-installation result.")
        }
        try require(
            !touchedKeys && fm.fileExists(atPath: busyHome.path)
                && fm.fileExists(atPath: busyApp.path), "Busy installation was modified.")

        let (retryRoot, retryHome, retryApp, retryPlan) = try fixture()
        defer { try? fm.removeItem(at: retryRoot) }
        do {
            _ = try Uninstaller.remove(
                retryPlan,
                deleteCredentials: { _ in throw UninstallError("simulated Keychain rejection") })
            throw UninstallError("Keychain failure was hidden.")
        } catch let error as UninstallError {
            try require(
                error.message == "simulated Keychain rejection", "Wrong Keychain failure result.")
        }
        try require(
            !fm.fileExists(atPath: retryHome.path) && fm.fileExists(atPath: retryApp.path),
            "Failed Keychain cleanup removed the app.")
        let retryResult = try Uninstaller.remove(retryPlan, deleteCredentials: { _ in })
        try require(retryResult.complete, "Retry after partial cleanup failed.")

        let (safeRoot, safeHome, safeApp, safePlan) = try fixture()
        defer { try? fm.removeItem(at: safeRoot) }
        let unsafePlan = UninstallPlan(
            parentPID: getpid(), dataPath: safeRoot.path,
            applicationPath: safeApp.path, applicationIdentity: safePlan.applicationIdentity,
            testRoot: safeRoot.path)
        do {
            _ = try Uninstaller.remove(unsafePlan, deleteCredentials: { _ in touchedKeys = true })
            throw UninstallError("Unsafe parent path was accepted.")
        } catch let error as UninstallError {
            try require(error.message.contains("unexpected"), "Unexpected path-validation result.")
        }
        let external = safeRoot.appendingPathComponent("ExternalData")
        try fm.moveItem(at: safeHome, to: external)
        try fm.createSymbolicLink(at: safeHome, withDestinationURL: external)
        var symlinkRejected = false
        do {
            _ = try Uninstaller.remove(safePlan, deleteCredentials: { _ in touchedKeys = true })
        } catch { symlinkRejected = true }
        try require(symlinkRejected, "Data symlink was accepted.")
        try require(
            fm.fileExists(atPath: external.appendingPathComponent("database").path),
            "Symlink target was modified.")
        try fm.removeItem(at: safeHome)
        try fm.moveItem(at: external, to: safeHome)
        let moved = safeRoot.appendingPathComponent("Old.app")
        try fm.moveItem(at: safeApp, to: moved)
        try fm.copyItem(at: moved, to: safeApp)
        do {
            _ = try Uninstaller.remove(safePlan, deleteCredentials: { _ in touchedKeys = true })
            throw UninstallError("Replacement application was removed.")
        } catch let error as UninstallError {
            try require(
                error.message.contains("moved or replaced"), "Unexpected app-identity result.")
        }
        try require(
            !touchedKeys && fm.fileExists(atPath: safeHome.path), "Invalid plan changed user data.")
        // Run the actual copied helper as a separate process. It must wait until
        // the fake parent exits and then delete its own executable and plan.
        let (processRoot, processHome, processApp, _) = try fixture()
        defer { try? fm.removeItem(at: processRoot) }
        let helperFolder = fm.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("tidymail-uninstaller-\(UUID().uuidString)")
        try fm.createDirectory(
            at: helperFolder, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        defer { try? fm.removeItem(at: helperFolder) }
        let executable = helperFolder.appendingPathComponent("Uninstall TidyMail")
        try fm.copyItem(at: URL(fileURLWithPath: CommandLine.arguments[0]), to: executable)
        let parent = Process()
        parent.executableURL = URL(fileURLWithPath: "/bin/sleep")
        parent.arguments = ["1"]
        try parent.run()
        let processPlan = try UninstallPlan.create(
            home: processHome, application: processApp,
            parentPID: parent.processIdentifier, testRoot: processRoot)
        let manifest = helperFolder.appendingPathComponent("plan.json")
        try JSONEncoder().encode(processPlan).write(to: manifest)
        let child = Process()
        child.executableURL = executable
        child.arguments = ["--test-plan", manifest.path]
        child.currentDirectoryURL = helperFolder
        child.standardOutput = FileHandle.nullDevice
        child.standardError = FileHandle.nullDevice
        try child.run()
        Thread.sleep(forTimeInterval: 0.2)
        try require(
            fm.fileExists(atPath: processHome.path), "Helper removed data before the parent exited."
        )
        parent.waitUntilExit()
        child.waitUntilExit()
        try require(child.terminationStatus == 0, "Separate helper failed.")
        try require(
            !fm.fileExists(atPath: processHome.path) && !fm.fileExists(atPath: processApp.path)
                && !fm.fileExists(atPath: helperFolder.path),
            "Helper left data, app or its own files behind.")
        print(
            "PASS: complete uninstall and Keychain deletion; unrelated files preserved; busy installation rejected; partial cleanup retry; unsafe paths and replacement apps rejected; separate helper waits for exit and deletes itself."
        )
    }
}
