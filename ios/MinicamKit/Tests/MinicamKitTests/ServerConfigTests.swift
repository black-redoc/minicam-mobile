import Foundation
import Testing
@testable import MinicamKit

@Suite struct ServerConfigTests {
    @Test func serverConfig_acceptsHttpsAndLanHttp_rejectsPublicHttp() throws {
        #expect(try ServerConfig(parsing: "https://cam.example.com").baseURL.absoluteString
            == "https://cam.example.com")
        #expect(try ServerConfig(parsing: " http://192.168.1.20:8080/ ").baseURL.absoluteString
            == "http://192.168.1.20:8080")
        for lan in ["http://10.0.0.5", "http://172.16.0.1", "http://172.31.9.9",
                    "http://127.0.0.1:8000", "http://localhost:8000", "http://raspberrypi.local"] {
            #expect(throws: Never.self) { try ServerConfig(parsing: lan) }
        }

        #expect(throws: ServerConfigError.insecureRemoteHost) {
            try ServerConfig(parsing: "http://cam.example.com")
        }
        #expect(throws: ServerConfigError.insecureRemoteHost) {
            try ServerConfig(parsing: "http://172.32.0.1")
        }
        #expect(throws: ServerConfigError.insecureRemoteHost) {
            try ServerConfig(parsing: "http://8.8.8.8")
        }
        #expect(throws: ServerConfigError.unsupportedScheme) {
            try ServerConfig(parsing: "ftp://192.168.1.2")
        }
        #expect(throws: ServerConfigError.invalidURL) { try ServerConfig(parsing: "not a url") }
    }

    @Test func serverConfig_dropsPathAndLowercasesHost() throws {
        let config = try ServerConfig(parsing: "HTTPS://Cam.Example.com/some/path?x=1")
        #expect(config.baseURL.absoluteString == "https://cam.example.com")
        #expect(config.isWAN)
    }

    @Test func streamUrl_matchesSchemeAndEncodesName() throws {
        let wan = try ServerConfig(parsing: "https://cam.example.com")
        #expect(wan.streamURL(camera: "front door", token: "abc").absoluteString
            == "wss://cam.example.com/ws/stream/front%20door?token=abc")

        let lan = try ServerConfig(parsing: "http://192.168.1.20:8080")
        #expect(lan.streamURL(camera: "a/b", token: "t").absoluteString
            == "ws://192.168.1.20:8080/ws/stream/a%2Fb?token=t")
    }
}
