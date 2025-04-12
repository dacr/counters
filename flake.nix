{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    utils.url = "github:numtide/flake-utils";
    sbt.url = "github:zaninime/sbt-derivation";
    sbt.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, utils, sbt }:
  utils.lib.eachDefaultSystem (system:
  let
    pkgs = import nixpkgs { inherit system; };
  in {
    # ---------------------------------------------------------------------------
    # nix develop
    devShells.default = pkgs.mkShell {
      buildInputs = [pkgs.sbt pkgs.metals pkgs.jdk21 pkgs.hello];
    };

    # ---------------------------------------------------------------------------
    # nix build
    packages.default = sbt.mkSbtDerivation.${system} {
      pname = "nix-counters";
      version = builtins.elemAt (builtins.match ''[^"]+"(.*)".*'' (builtins.readFile ./version.sbt)) 0;
      depsSha256 = "sha256-j1q6nOzhZcAR66MstYVKRDghikCDojf/SSxAwhFWiCk=";

      src = ./.;

      buildInputs = [pkgs.sbt pkgs.jdk21_headless pkgs.makeWrapper];

      buildPhase = "sbt Universal/packageZipTarball";

      installPhase = ''
          mkdir -p $out
          tar xf target/universal/counters.tgz --directory $out
          makeWrapper $out/bin/counters $out/bin/nix-counters \
            --set PATH ${pkgs.lib.makeBinPath [
              pkgs.gnused
              pkgs.gawk
              pkgs.coreutils
              pkgs.bash
              pkgs.jdk21_headless
            ]}
      '';
    };

    # ---------------------------------------------------------------------------
    # simple nixos services integration
    nixosModules.default = { config, pkgs, lib, ... }: {
      options = {
        services.counters = {
          enable = lib.mkEnableOption "counters";
          user = lib.mkOption {
            type = lib.types.str;
            description = "User name that will run the counters service";
          };
          ip = lib.mkOption {
            type = lib.types.str;
            description = "Listening network interface - 0.0.0.0 for all interfaces";
            default = "127.0.0.1";
          };
          port = lib.mkOption {
            type = lib.types.int;
            description = "Service counters listing port";
            default = 8080;
          };
          url = lib.mkOption {
            type = lib.types.str;
            description = "How this service is known/reached from outside";
            default = "http://127.0.0.1:8080";
          };
          prefix = lib.mkOption {
            type = lib.types.str;
            description = "Service counters url prefix";
            default = "";
          };
          datastore = lib.mkOption {
            type = lib.types.str;
            description = "where counters stores its data";
            default = "/tmp/counters-cache-data";
          };
        };
      };
      config = lib.mkIf config.services.counters.enable {
        systemd.tmpfiles.rules = [
              "d ${config.services.counters.datastore} 0750 ${config.services.counters.user} ${config.services.counters.user} -"
        ];
        systemd.services.counters = {
          description = "Spy service";
          environment = {
            COUNTERS_LISTEN_IP   = config.services.counters.ip;
            COUNTERS_LISTEN_PORT = (toString config.services.counters.port);
            COUNTERS_PREFIX      = config.services.counters.prefix;
            COUNTERS_URL         = config.services.counters.url;
            COUNTERS_STORE_PATH  = config.services.counters.datastore;
          };
          serviceConfig = {
            ExecStart = "${self.packages.${pkgs.system}.default}/bin/nix-counters";
            User = config.services.counters.user;
            Restart = "on-failure";
          };
          wantedBy = [ "multi-user.target" ];
        };
      };
    };
    # ---------------------------------------------------------------------------

  });
}
