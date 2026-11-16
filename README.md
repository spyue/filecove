# FileCove
Self hosted github style file sharer including version history.

Built to self host my files and interact with media saved on my server pc. Mainly used for transferring
files between my computers for school as well as controlling music and video for streaming.

## Some Features
- Push and pull files between devices that run java
- Built in version history, retrieve with cove pull *file* --version *hash*
- Folder viewing, use ls *folder* to view a specific folder
- Send to a device, place files in a devices inbox
- One command client setup using cove setup (easy to add new devices)
- All using just java

## Features in progress
- Some sort of auto folder sync between selected devices
- Pushing and pulling entire folders
- Auto deletion of old versions (currently they don't auto delete causing storage overuse)
- Per-device tokens
- OpenBSD support
- install-server.sh server install script
- docker support

## Quick Guide
### Build
To generate the .jar file that holds the Server and Client run:

> sh scripts/build.sh 

### Run the server
run the server with the following filling in with your own values:

> FILECOVE_TOKEN=your-long-random-token \
> FILECOVE_DATA=/srv/filecove/data \
> FILECOVE_HOST=100.x.y.z \
> java -cp filecove.jar Server

make a token with:

> tr -dc 'a-f0-9' < /dev/urandom | head -c 64; echo

### Set up on each device
- Get the .jar from before
- run the install-client.sh script for your device:
| System | Command | Install into |
| --- | --- | --- |
| Linux, for your user | 'sh scripts/install-client.sh filecove.jar' | '~/.local' |
| Android (termux) | 'sh scripts/install-client.sh filecove.jar "$PREFIX"' | termux's own folders |
| Linux, for all users | 'sudo sh scripts/install-client.sh filecove.jar /usr/local' | '/usr/local' |

### Make sure its bin folder is in your path
Termux, and /usr/local on Debian: already on path \
Debian, ~/.local: log out and back in once. Debian adds ~/.local/bin whenever that folder exists \
NixOS: add home.sessionPath = [ "$HOME/.local/bin" ] to your Home Manager config rebuild and relog \
anything else just add export PATH="$HOME/.local/bin:$PATH" to ~/.profile, then relog \
check with type cove which should return where it is installed

### Connect to the server
using: 

> cove setup http://YOUR-SERVER:8800 YOUR-TOKEN DEVICE-NAME

## Using:

| Command | Use |
| --- | --- |
| cove ls [FOLDER] [--all] | Lists 1 folder, --all lists every file |
| cove push FILE [NAME] | Sends a file to the server, name ending in / puts it in that folder |
| cove pull NAME [LOCAL] [--version hash] | Download the current or older version |
| cove log [NAME] | Shows history |
| cove rm NAME | Removes file from the server (leaves old versions) |
| cove send FILE... DEVICE | Puts files into a devices inbox |
| cove inbox [--get] | List or download the files in the inbox to ~/Inbox |
| cove setup SERVER TOKEN [DEVICE] [--force] | create the devices config |

## Configuration

**Server**
| Variable | Meaning | Default |
| --- | --- | --- |
| FILECOVE_TOKEN | Shared secret clients send | none |
| FILECOVE_DATA | Storage location for history and files | ~/filecove-data |
| FILECOVE_HOST | Address to listen on | 0.0.0.0 |
| FILECOVE_PORT | Port to listen on | 8800 |

**Client** \
~/.config/filecove/config.properties, written by cove setup
server=http://YOUR-SERVER:8800
token=YOUR-TOKEN
device=DEVICE-NAME
