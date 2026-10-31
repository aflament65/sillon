package fr.aflament.sillon;

import android.service.notification.NotificationListenerService;

/**
 * Service vide exigé par Android : une fois activé dans « Accès aux notifications »,
 * il autorise Sillon à lire les infos « en cours de lecture » des autres applis
 * (titre, artiste, vignette) pendant l'enregistrement du son du téléphone.
 * Sillon ne lit ni ne garde le contenu des notifications.
 */
public class NowPlayingListener extends NotificationListenerService {
}
