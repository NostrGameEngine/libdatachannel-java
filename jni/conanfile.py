from conan import ConanFile

statically_linked_platforms = {
    "Windows",
    "Macos",
    "Android",
    "iOS",
}


class LibDataChannel(ConanFile):
    settings = "os", "arch", "compiler", "build_type"
    requires = "openssl/3.6.0"
    generators = "CMakeDeps"

    def configure(self):
        self.options["openssl"].shared = f"{self.settings.os}" not in statically_linked_platforms
        self.options["openssl"].no_apps = True
        self.options["openssl"].no_zlib = True
