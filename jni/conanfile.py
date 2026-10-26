from conan import ConanFile

class LibDataChannel(ConanFile):
    settings = "os", "arch", "compiler", "build_type"
    requires = "openssl/3.6.0"
    generators = "CMakeDeps"

    def configure(self):
        self.options["openssl"].shared = False
        if str(self.settings.os) != "Windows":
            self.options["openssl"].fPIC = True
        self.options["openssl"].no_apps = True
        self.options["openssl"].no_zlib = True
        self.options["openssl"].no_module = True
        self.options["openssl"].no_dso = True
        self.options["openssl"].no_engine = True
        self.options["openssl"].no_fips = True
        self.options["openssl"].no_autoload_config = True
