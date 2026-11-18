# SSH Terminal

An Android app to save SSH servers and open a full terminal on them from your phone.

## Install

Open the **Releases** page on your phone, download `SshTerminal.apk` from the newest release and open it.
Every push to `main` tests SSH logins against a real server and builds a new APK (see **Actions**).

## Features

- **Saved servers**: name, address, port, user, password or key, optional command to run after login.
- **Quick connect**: type `user@host:port` and go.
- **Real terminal**: colours, vim, htop, tmux, nano. Pinch to zoom, drag to scroll.
- **Extra keys row**: ESC, CTRL, ALT, TAB, arrows (hold to repeat), HOME/END, PGUP/PGDN, ^C ^D ^Z.
- **Several sessions** as tabs. They stay connected in the background.
- **SSH keys**: create Ed25519 / RSA / ECDSA keys or import your own (with passphrase).
  "Install my key on this server" adds it to `authorized_keys` for you.
- **Security**: passwords and private keys are encrypted with the Android Keystore.
  Server fingerprints are checked on first connect and you're warned if they change.

Built with [xterm.js](https://xtermjs.org) (terminal) and [JSch](https://github.com/mwiede/jsch) (SSH).
