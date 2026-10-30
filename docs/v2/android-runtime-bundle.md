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
  "kernelAppend": "console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw",
  "files": {
    "bin/qemu-system-aarch64": "SHA256_HEX",
    "boot/Image": "SHA256_HEX",
    "boot/initramfs": "SHA256_HEX",
    "disk/rootfs.qcow2": "SHA256_HEX"
  }
}
```

Toutes les entrées de fichiers exécutables ou images invitées doivent être listées dans `files`. Les chemins absolus, les segments `..`, les chemins Windows, les archives excessives et les empreintes incorrectes sont refusés. Le binaire QEMU est en plus contrôlé comme ELF64 little-endian AArch64 au démarrage.

## Démarrage

1. L'utilisateur importe l'archive.
2. L'application extrait dans un répertoire de staging et vérifie toutes les empreintes.
3. Le runtime actif est remplacé atomiquement.
4. Au premier démarrage seulement, le disque QCOW2 Alpine fourni est copié vers le stockage persistant privé de l’application. Les mises à jour du runtime ne remplacent jamais ce disque utilisateur.
5. L'utilisateur démarre explicitement la VM.
6. Un service Android de premier plan possède le processus QEMU.
7. Le disque invité est conservé entre les mises à jour et n'est jamais tronqué.
8. Les journaux QEMU et console série sont conservés dans le stockage privé.

## Limites à ne pas masquer

Le pipeline de build produit une base Alpine ARM64 avec noyau virt, initramfs, disque ext4 QCOW2 et OpenRC. Le service Android transfère les ports Mainsail (8080) et Moonraker (7125) vers loopback et vérifie l’état de Moonraker/Klipper. Ces éléments doivent encore passer la CI puis un test sur téléphone ARM64 physique. Docker et l’accès MCU USB ne sont pas encore validés. Le bundle de production doit être construit avec QEMU Bionic relocatable, sa fermeture complète de bibliothèques, un noyau/initramfs Alpine testés et des licences tierces. Le test final reste un démarrage sur téléphone ARM64 physique.
