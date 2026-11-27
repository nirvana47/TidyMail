import CryptoKit
import Darwin
import Foundation
import Security

struct UninstallError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    init(_ message: String) { self.message = message }
}

enum InstallationCredentials {
    static func query(for home: URL) -> [String: Any] {
        let digest = SHA256.hash(data: Data(home.path.utf8)).map { String(format: "%02x", $0) }
            .joined()
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "work.tidymail.desktop.\(digest)",
            kSecAttrAccount as String: "local-installation",
        ]
    }
    static func delete(for home: URL) throws {
        let status = SecItemDelete(query(for: home) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw UninstallError(
                "The local database, backups and logs were removed, but macOS did not allow removal of TidyMail’s Keychain credentials. The app was kept so you can retry. Unlock your login Keychain, reopen TidyMail, and choose Uninstall again."
            )
        }
    }
}

struct UninstallPlan: Codable {
    let parentPID: Int32
    let dataPath: String
    let applicationPath: String
    let applicationIdentity: String
    let testRoot: String?

    static func identity(_ path: URL) throws -> String {
        var info = stat()
        guard lstat(path.path, &info) == 0, (info.st_mode & S_IFMT) == S_IFDIR else {
            throw UninstallError("The application folder has changed. Nothing was removed.")
        }
        return "\(info.st_dev):\(info.st_ino)"
    }
    static func create(home: URL, application: URL, parentPID: Int32, testRoot: URL? = nil) throws
        -> UninstallPlan
    {
        UninstallPlan(
            parentPID: parentPID, dataPath: home.path,
            applicationPath: application.path, applicationIdentity: try identity(application),
            testRoot: testRoot?.path)
    }
    // Paths are never accepted as unrestricted deletion targets. Production always
    // uses this macOS user's dedicated data folder and the exact app inode approved.
    func validate() throws -> (URL, URL) {
        let fm = FileManager.default
        let data = URL(fileURLWithPath: dataPath).standardizedFileURL
        let app = URL(fileURLWithPath: applicationPath).standardizedFileURL
        let expected: URL
        if let rootPath = testRoot {
            let root = URL(fileURLWithPath: rootPath).standardizedFileURL
            let temp = fm.temporaryDirectory.resolvingSymlinksInPath().standardizedFileURL
            guard root.deletingLastPathComponent().resolvingSymlinksInPath() == temp,
                root.lastPathComponent.hasPrefix("tidymail-uninstall-check-"),
                app.deletingLastPathComponent() == root
            else {
                throw UninstallError("Invalid uninstall test location. Nothing was removed.")
            }
            expected = root.appendingPathComponent("Data")
        } else {
            expected =
                fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("TidyMail").standardizedFileURL
        }
        guard data == expected,
            data.resolvingSymlinksInPath() == expected.resolvingSymlinksInPath(),
            app.pathExtension == "app", app.resolvingSymlinksInPath() == app,
            !data.path.hasPrefix(app.path + "/"), !app.path.hasPrefix(data.path + "/")
        else {
            throw UninstallError("The installation paths are unexpected. Nothing was removed.")
        }
        // Reject a symlink at the data root rather than following it or claiming
        // that data at an external destination was erased.
        var dataInfo = stat()
        if lstat(data.path, &dataInfo) == 0 && (dataInfo.st_mode & S_IFMT) != S_IFDIR {
            throw UninstallError(
                "TidyMail’s data folder is a link or an unexpected file. Nothing was removed.")
        }
        guard try Self.identity(app) == applicationIdentity,
            let info = NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist")),
            info["CFBundleIdentifier"] as? String == "work.tidymail.desktop"
        else {
            throw UninstallError(
                "The app was moved or replaced after uninstall was confirmed. Nothing was removed.")
        }
        return (data, app)
    }
}

struct UninstallResult {
    let message: String
    let complete: Bool
}

enum Uninstaller {
    static func auxiliaryPaths(_ plan: UninstallPlan) -> [URL] {
        let fm = FileManager.default
        let library: URL
        if let root = plan.testRoot {
            library = URL(fileURLWithPath: root).appendingPathComponent("Library")
        } else {
            library = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        }
        return [
            "Caches/work.tidymail.desktop", "Preferences/work.tidymail.desktop.plist",
            "Saved Application State/work.tidymail.desktop.savedState",
            "HTTPStorages/work.tidymail.desktop", "WebKit/work.tidymail.desktop",
            "Cookies/work.tidymail.desktop.binarycookies",
        ].map { library.appendingPathComponent($0) }
    }
    static func waitForParent(_ pid: Int32) throws {
        guard pid > 1, pid != getpid() else {
            throw UninstallError("Invalid uninstall parent process.")
        }
        for _ in 0..<600 {
            if kill(pid, 0) != 0 && errno == ESRCH { return }
            Thread.sleep(forTimeInterval: 0.1)
        }
        throw UninstallError(
            "TidyMail has not finished quitting. Nothing was removed. Quit all copies and try Uninstall again."
        )
    }
    static func remove(
        _ plan: UninstallPlan,
        deleteCredentials: (URL) throws -> Void = InstallationCredentials.delete
    ) throws -> UninstallResult {
        let fm = FileManager.default
        let (data, app) = try plan.validate()
        var fd: Int32 = -1
        defer {
            if fd >= 0 {
                flock(fd, LOCK_UN)
                close(fd)
            }
        }
        if fm.fileExists(atPath: data.path) {
            fd = Darwin.open(
                data.appendingPathComponent("launcher.lock").path, O_CREAT | O_RDWR | O_CLOEXEC,
                0o600)
            guard fd >= 0, flock(fd, LOCK_EX | LOCK_NB) == 0 else {
                throw UninstallError(
                    "Another copy of TidyMail is using its data. Nothing was removed. Quit all copies and try Uninstall again."
                )
            }
            do { try fm.removeItem(at: data) } catch {
                throw UninstallError(
                    "Some local data could not be removed. The app and its Keychain credentials were kept so you can retry. Check the TidyMail folder’s permissions before trying again."
                )
            }
        }
        do {
            for path in auxiliaryPaths(plan) {
                if fm.fileExists(atPath: path.path) { try fm.removeItem(at: path) }
            }
            if plan.testRoot == nil {
                UserDefaults.standard.removePersistentDomain(forName: "work.tidymail.desktop")
                UserDefaults.standard.synchronize()
            }
        } catch {
            throw UninstallError(
                "The main database folder was removed, but some TidyMail settings or cache files could not be removed. The app and its Keychain credentials were kept so you can retry."
            )
        }
        try deleteCredentials(data)
        // Recheck identity immediately before deleting the app, including when
        // retrying after data has already been deleted by an earlier attempt.
        guard try UninstallPlan.identity(app) == plan.applicationIdentity else {
            throw UninstallError(
                "Local data and credentials were removed. The app was replaced while uninstalling; move the remaining app to Trash yourself."
            )
        }
        do { try fm.removeItem(at: app) } catch {
            return UninstallResult(
                message:
                    "All local TidyMail data and saved credentials were removed. The application could not be removed from this location. Move TidyMail.app to Trash to finish.",
                complete: false)
        }
        return UninstallResult(
            message:
                "TidyMail, its local mail data, backups, logs and saved credentials have been removed.",
            complete: true)
    }
}
