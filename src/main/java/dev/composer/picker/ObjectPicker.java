package dev.composer.picker;

import java.util.List;

/** Выбирает объекты библиотеки по тексту запроса. Возвращает id, самые подходящие — первыми; пустой список — «не знаю». */
public interface ObjectPicker {

    List<String> pick(String query, int max);
}
