# AMC roadmap

## v0.1 — готово как прототип
- [x] Java multi-module source
- [x] Scalar autograd, basic recurrent model, optimizer
- [x] Exact-address differentiable value memory
- [x] SET/GET generator, train/eval, baseline
- [x] Gradient checks and reproducible seed
- [x] Basic checkpoint and runtime smoke benchmark

## v0.2 — обучаемая память
- [ ] Softmax attention over slots, query/key learning
- [ ] Differentiable erase/add memory update
- [ ] Gradient tests for addressing/write operations
- [ ] Attention-free and GRU baselines
- [ ] Unit tests for conflicting writes, generalization to unseen sequence lengths

## v0.3 — адаптивные вычисления
- [ ] Halting policy with explicit compute penalty
- [ ] Gradient-safe training through the policy
- [ ] Measure empirical compute/quality Pareto frontier

## v0.4 — производительность
- [ ] Replace scalar autograd by tensors and supported GPU backend
- [ ] Batch training and checkpoint with optimizer/config
- [ ] Add Vector API CPU kernels after BLAS comparison
- [ ] Profiling, JMH, FLOP estimates and variance across runs
- [ ] Bounded parallel data preparation and independent experiment execution

## v0.5 — языковые эксперименты
- [x] Пилот генерации арифметических Java-методов с рекуррентным декодером, отдельной выборкой и проверкой сгенерированного кода
- [x] Пилот генерации Java-методов для массивов с циклом, условием и проверкой новых сочетаний фильтра и операции
- [x] Общая модель для арифметики, массивов, строк и коллекций с раздельной оценкой навыков
- [x] Генерация целых `Solution.java`, раздельный корпус и проверка сборки и поведения в контейнерах
- [ ] BPE tokenizer and licensed text datasets
- [ ] Autoregressive causal training and generation
- [ ] Controlled parameter/compute-matched Transformer comparison

### Оценка покрытия Java

- Язык и стандартная библиотека. Проверять ООП, generics, аннотации, исключения, records, сопоставление с образцом, коллекции, ввод-вывод и конкурентность отдельными наборами. Состав тем можно сверять с [учебными разделами Java](https://dev.java/learn/).
- Контроль на независимых задачах. Измерять компиляцию и прохождение тестов на Java-разделе [MultiPL-E](https://github.com/nuprl/MultiPL-E), сохраняя его вне обучения.
- Проектный уровень. Отдельно измерять изменения в нескольких файлах, сборку и исправление задач реальных репозиториев. Для внешнего сравнения подходит Java-часть [SWE-bench Multilingual](https://www.swebench.com/multilingual.html).
