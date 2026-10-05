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
      depsSha256 = "sha256-NUpHtfX8RH1Rc3JiS20JPMDGYTCCSvVBNrKv2LcX1lQ=";

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
          mailFrom = lib.mkOption {
            type = lib.types.str;
            description = "Sender of the emails sent by counters, such as the registration email validation";
            default = "counters@localhost";
          };
          mailReplyTo = lib.mkOption {
            type = lib.types.nullOr lib.types.str;
            description = "Reply-To of the emails sent by counters";
            default = null;
          };
          smtpHost = lib.mkOption {
            type = lib.types.nullOr lib.types.str;
            description = "SMTP server, without it emails are not sent but only logged";
            default = null;
          };
          smtpPort = lib.mkOption {
            type = lib.types.int;
            description = "SMTP server port";
            default = 465;
          };
          smtpTls = lib.mkOption {
            type = lib.types.enum [ "implicit" "starttls" "none" ];
            description = "implicit (TLS from the first byte, usually port 465), starttls (usually port 587) or none";
            default = "implicit";
          };
          smtpUsername = lib.mkOption {
            type = lib.types.nullOr lib.types.str;
            description = "SMTP authentication user name";
            default = null;
          };
          environmentFile = lib.mkOption {
            type = lib.types.nullOr lib.types.path;
            description = "Environment file for secrets, COUNTERS_SMTP_PASSWORD, kept out of the nix store";
            default = null;
          };
        };
      };
      config = lib.mkIf config.services.counters.enable {
        systemd.tmpfiles.rules = [
              "d ${config.services.counters.datastore} 0750 ${config.services.counters.user} ${config.services.counters.user} -"
        ];
        systemd.services.counters = {
          description = "Counters service";
          environment = {
            COUNTERS_LISTEN_IP   = config.services.counters.ip;
            COUNTERS_LISTEN_PORT = (toString config.services.counters.port);
            COUNTERS_PREFIX      = config.services.counters.prefix;
            COUNTERS_URL         = config.services.counters.url;
            COUNTERS_STORE_PATH  = config.services.counters.datastore;
            COUNTERS_MAIL_FROM   = config.services.counters.mailFrom;
            COUNTERS_SMTP_PORT   = (toString config.services.counters.smtpPort);
            COUNTERS_SMTP_TLS    = config.services.counters.smtpTls;
          } // lib.optionalAttrs (config.services.counters.mailReplyTo != null) {
            COUNTERS_MAIL_REPLY_TO = config.services.counters.mailReplyTo;
          } // lib.optionalAttrs (config.services.counters.smtpHost != null) {
            COUNTERS_SMTP_HOST = config.services.counters.smtpHost;
          } // lib.optionalAttrs (config.services.counters.smtpUsername != null) {
            COUNTERS_SMTP_USERNAME = config.services.counters.smtpUsername;
          };
          serviceConfig = {
            ExecStart = "${self.packages.${pkgs.system}.default}/bin/nix-counters";
            User = config.services.counters.user;
            Restart = "on-failure";
          } // lib.optionalAttrs (config.services.counters.environmentFile != null) {
            EnvironmentFile = config.services.counters.environmentFile;
          };
          wantedBy = [ "multi-user.target" ];
        };
      };
    };
    # ---------------------------------------------------------------------------

  });
}
