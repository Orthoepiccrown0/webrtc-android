# DirectStream SDK documentation

Start with the [quick start](../README.md#quick-start) for installation, process initialization, obtaining the `calls` client, login/name updates/logout, and Compose UI. The [full integration guide](../README.md#integrate-into-your-application) covers Firebase configuration, push forwarding, permissions and host responsibilities.

| Guide                           | Use it for                                                                           |
| ------------------------------- | ------------------------------------------------------------------------------------ |
| [Architecture](ARCHITECTURE.md) | Modules, client ownership, registration/name lifecycle and custom-provider contracts |
| [Deployment](DEPLOYMENT.md)     | Fresh Firebase/Worker/D1 setup, v1 API, private credentials, TURN and operations     |
| [Coordination](COORDINATION.md) | D1 authority, admission transactions, realtime projections, recovery and retention   |
| [Codecs](CODECS.md)             | Codec policies, capability discovery, live negotiation and custom signaling          |
| [Validation](VALIDATION.md)     | Repository checks, isolated emulator tests and physical-device/cloud release gates   |
| [Release builds](RELEASE.md)    | Showcase APK/AAB export, application signing, R8 and crash symbols                   |
| [Publication](PUBLICATION.md)   | SDK artifacts, SemVer/API baseline, local validation and Maven Central releases      |
| [CI and tag releases](CI.md)    | Automatic Maven releases, tag versions, Android version codes and GitHub secrets     |
| [Dependencies](DEPENDENCIES.md) | Generated resolved runtime coordinates and upstream license declarations for review  |

Install SDK `1.0.0` from Maven Central using `io.github.orthoepiccrown0:directstream:1.0.0`. The showcase consumes the published starter by default. SDK contributors can use local modules with `-PshowcaseUseLocalSdk=true`. Original project source uses [Apache 2.0](../LICENSE). Backend API, push and codec envelopes independently use protocol v1. Example configuration files contain placeholders; supply your own ignored local configuration. Android builds and validation commands do not deploy cloud services.
