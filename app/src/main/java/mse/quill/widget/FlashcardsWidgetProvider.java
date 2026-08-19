package mse.quill.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import mse.quill.MainActivity;
import mse.quill.R;

/**
 * Home-screen widget showing flashcards, stacked in one card the way
 * {@link CollectionsWidgetProvider} stacks pinned notes and collections: cards due right now
 * (front text only — the widget is a nudge to study, not a way to see answers for free), then
 * every deck below it.
 */
public class FlashcardsWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_flashcards);

            views.setRemoteAdapter(R.id.widget_due_cards_list,
                    new Intent(context, DueCardsRemoteViewsService.class));
            views.setEmptyView(R.id.widget_due_cards_list, R.id.widget_due_cards_empty);

            views.setRemoteAdapter(R.id.widget_flashcard_decks_list,
                    new Intent(context, FlashcardDecksRemoteViewsService.class));
            views.setEmptyView(R.id.widget_flashcard_decks_list, R.id.widget_flashcard_decks_empty);

            // FLAG_MUTABLE: see CollectionsWidgetProvider's template for why an immutable one
            // would silently drop each row's fillInIntent extras.
            PendingIntent template = PendingIntent.getActivity(
                    context, 0, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.widget_due_cards_list, template);
            views.setPendingIntentTemplate(R.id.widget_flashcard_decks_list, template);

            manager.updateAppWidget(appWidgetId, views);
        }
    }
}
