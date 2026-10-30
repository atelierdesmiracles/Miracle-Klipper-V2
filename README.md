# Miracle Klipper V2

Nouvelle base du projet **Miracle Klipper V2**.

## Objectif
Application Android ARM64 sans root, utilisant une machine virtuelle Alpine Linux ARM64 exécutée avec QEMU TCG. Docker Engine sera exécuté dans la VM, avec Klipper, Moonraker et Mainsail.

## État du développement
- Banc Android de diagnostic V2 compilé et testé en CI.
- Première couche d'intégration QEMU (générateur de commande, précontrôles et contrôleur de processus) ajoutée.
- QEMU Android, noyau/rootfs Alpine, Docker et la pile Klipper restent à intégrer et à valider sur appareil physique.

## Dépôt et branche de travail
- Branche de développement : `rewrite/miracle-klipper-v2`
- Le travail de migration est suivi sur cette branche.
- Ne pas fusionner dans `main` tant que les jalons VM et tests physiques ne sont pas validés.

## Architecture et suivi
- `docs/v2/architecture.md`
- `docs/v2/roadmap.md`
- `docs/v2/decisions.md`
- `docs/v2/phase-2-qemu-integration.md`

Le projet conserve également l'application existante et ses sources historiques pendant la transition.
