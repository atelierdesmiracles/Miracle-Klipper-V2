# Docker optionnel dans le guest Alpine

Docker n'est pas requis pour démarrer Klipper, Moonraker ou Mainsail dans le runtime de base. Ces services sont installés comme processus Alpine natifs afin de réduire les dépendances et de valider d'abord le fonctionnement du guest.

Une commande d'installation optionnelle est disponible dans le manager :

```sh
miracle-klipper-manager docker-status
miracle-klipper-manager docker-install
miracle-klipper-manager docker-start
miracle-klipper-manager docker-stop
```

Avant l'installation, le manager vérifie les options noyau exposées, notamment namespaces, cgroups, OverlayFS, veth, bridge, netfilter et NAT. Si le noyau ne publie pas sa configuration, le contrôle est explicitement signalé comme incomplet. Le démarrage de Docker peut encore échouer si le montage cgroup ou le pilote de stockage ne convient pas.

Ne pas considérer Docker comme opérationnel avant d'avoir vérifié dans le guest :
- `docker version` et `docker info`;
- `docker run --rm hello-world`;
- DNS et réseau des conteneurs;
- stockage persistant et redémarrage;
- arrêt propre et reprise après redémarrage de la VM.

Le daemon Docker n'est pas activé automatiquement dans le runtime initial. L'utilisateur choisit explicitement de l'installer et de le démarrer. Cette fonction reste expérimentale tant que les tests ci-dessus n'ont pas passé leur gate sur un téléphone ARM64 physique.
