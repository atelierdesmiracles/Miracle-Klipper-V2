# Runtime bundle V2 — contrat d'intégration Android

L'application V2 peut importer une archive runtime ZIP depuis le sélecteur de fichiers Android. L'archive est extraite dans le stockage privé de l'application, vérifiée, puis activée par échange atomique de répertoires.

## Structure obligatoire

```
manifest.json
bin/qemu-system-aarch64
lib/                         # bibliothèques Bionic requises par QEMU
boot/Image                   # noyau ARM64 compatible QEMU virt
boot/initramfs               # initramfs Alpine ARM64
```

Exemple de manifeste :

```json
{
  "schemaVersion": 1,
  "architecture": "aarch64",
  "qemuBinary": "bin/qemu-system-aarch64",
  "kernel": "boot/Image",
  "initrd": "boot/initramfs",
  "diskSizeMb": 8192,
  "memoryMb": 1024,
  "cpuCount": 2,
  "kernelAppend": "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init",
  "files": {
    "bin/qemu-system-aarch64": "SHA256_HEX",
    "boot/Image": "SHA256_HEX",
    "boot/initramfs": "SHA256_HEX"
  }
}
```

Toutes les entrées de fichiers exécutables ou images invitées doivent être listées dans `files`. Les chemins absolus, les segments `..`, les chemins Windows, les archives excessives et les empreintes incorrectes sont refusés. Le binaire QEMU est en plus contrôlé comme ELF64 little-endian AArch64 au démarrage.

## Démarrage

1. L'utilisateur importe l'archive.
2. L'application extrait dans un répertoire de staging et vérifie toutes les empreintes.
3. Le runtime actif est remplacé atomiquement.
4. L'utilisateur démarre explicitement la VM.
5. Un service Android de premier plan possède le processus QEMU.
6. Le disque invité est créé une seule fois et n'est jamais tronqué.
7. Les journaux QEMU et console série sont conservés dans le stockage privé.

## Limites à ne pas masquer

Ce contrat et le service Android intègrent le cycle de vie hôte de QEMU. Ils ne prouvent pas que le noyau/initramfs fourni démarre Alpine, que Docker fonctionne dans le noyau invité, ni que Klipper accède à un MCU USB. Le bundle de production doit être construit avec QEMU Bionic relocatable, sa fermeture complète de bibliothèques, un noyau/initramfs Alpine testés et des licences tierces. Le test final reste un démarrage sur téléphone ARM64 physique.
