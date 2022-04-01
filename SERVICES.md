# Services

LibADB only supports local services which can be requested through `AbsAdbConnectionManager#openStream(String)` or
`AdbConnection#open(String)`.

- `shell:command arg1 arg2 ...`

  Run `command arg1 arg2 ...` in a shell on the device, and return its output and error streams. Note that arguments
  must be separated by spaces. If an argument contains a space, it must be quoted with double-quotes. Arguments cannot
  contain double quotes or things will go very wrong.

- `shell:`

  Start an interactive shell session on the device. Redirect stdin/stdout/stderr as appropriate.

- `dev:<path>`

  Opens a device file and connects the client directly to it for read/write purposes. Useful for debugging, but may 
  require special privileges and thus may not run on all devices. `<path>` is a full path from the root of the
  filesystem.

- `tcp:<port>`

  Tries to connect to tcp port `<port>` on localhost.

- `tcp:<port>:<server-name>`

  Tries to connect to tcp port `<port>` on machine `<server-name>` from the device. This can be useful to debug some
  networking/proxy issues that can only be revealed on the device itself.

- `local:<path>`

  Tries to connect to a Unix domain socket `<path>` on the device.

- `localreserved:<path>`/
  `localabstract:<path>`/
  `localfilesystem:<path>`

  Variants of `local:<path>` that are used to access other Android socket namespaces.

- `sync:`

  This starts the file synchronization service, used to implement "adb push" and "adb pull". Since this service is
  pretty complex, it will be detailed in a companion document named SYNC.TXT

- `reverse:<forward-command>`

  This implements the 'adb reverse' feature, i.e. the ability to reverse socket connections from a device to the host.
  `<forward-command>` is one of the forwarding commands that are described above, as in:

      list-forward
      forward:<local>;<remote>
      forward:norebind:<local>;<remote>
      killforward-all
      killforward:<local>

  Note that in this case, <local> corresponds to the socket on the device
  and <remote> corresponds to the socket on the host.

  The output of reverse:list-forward is the same as host:list-forward
  except that <serial> will be just 'host'.

## Reference
- [SERVICES.TXT](https://android.googlesource.com/platform/packages/modules/adb/+/6a85258511fb13ebbbedba4e36616db4c6e970fb/SERVICES.TXT)
