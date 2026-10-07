import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import "./style.css";
const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        { path: "/", component: () => import("./views/Dashboard.vue") },
        { path: "/tasks", component: () => import("./views/Tasks.vue") },
        {
            path: "/tasks/:id",
            component: () => import("./views/TaskDetail.vue"),
        },
        { path: "/schedule", component: () => import("./views/Schedule.vue") },
        { path: "/team", component: () => import("./views/Team.vue") },
        { path: "/projects", component: () => import("./views/Projects.vue") },
        { path: "/:pathMatch(.*)*", redirect: "/" },
    ],
});
createApp(App).use(router).mount("#app");
